package com.iacross.flowpilot.engine.service;

import com.iacross.flowpilot.channel.contact.Contact;
import com.iacross.flowpilot.channel.contact.ContactService;
import com.iacross.flowpilot.channel.domain.ChannelConnection;
import com.iacross.flowpilot.channel.repository.ChannelConnectionRepository;
import com.iacross.flowpilot.channel.spi.ChannelAdapter;
import com.iacross.flowpilot.channel.spi.InboundMessage;
import com.iacross.flowpilot.channel.spi.OutboundMessage;
import com.iacross.flowpilot.engine.domain.Conversation;
import com.iacross.flowpilot.engine.domain.ConversationEvent;
import com.iacross.flowpilot.engine.domain.Message;
import com.iacross.flowpilot.engine.repository.ConversationEventRepository;
import com.iacross.flowpilot.engine.repository.ConversationRepository;
import com.iacross.flowpilot.engine.repository.MessageRepository;
import com.iacross.flowpilot.engine.spi.NodeContext;
import com.iacross.flowpilot.engine.spi.NodeExecutor;
import com.iacross.flowpilot.engine.spi.NodeResult;
import com.iacross.flowpilot.flow.domain.Flow;
import com.iacross.flowpilot.flow.domain.FlowGraph;
import com.iacross.flowpilot.flow.domain.FlowNode;
import com.iacross.flowpilot.flow.domain.FlowVersion;
import com.iacross.flowpilot.flow.repository.FlowRepository;
import com.iacross.flowpilot.flow.repository.FlowVersionRepository;
import com.iacross.flowpilot.shared.lock.ConversationLock;
import com.iacross.flowpilot.shared.security.AesGcmEncryptor;
import com.iacross.flowpilot.shared.tenant.RlsTenantInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Orchestration engine interpreter loop (TRD §5.4).
 *
 * On each inbound message:
 *   1. Resolve contact + channel connection (ContactService handles its own TX)
 *   2. Resolve or create conversation (short TX via TransactionTemplate)
 *   3. Acquire per-conversation Redis lock (outside TX — single-writer, TRD §6)
 *   4. Load conversation state (Redis hot → PG fallback for restart recovery)
 *   5. Execute nodes until a yield (AwaitInput / AwaitExternal / End)
 *   6. Persist state to PG + Redis, emit outbound messages
 *   7. Release lock
 *
 * TransactionTemplate is used for inner TXs to avoid Spring AOP self-call issues.
 * New node types plug in by implementing NodeExecutor — the loop is unchanged.
 */
@Service
public class FlowEngine {

    private static final Logger log = LoggerFactory.getLogger(FlowEngine.class);
    // Guard against infinite loops in misconfigured graphs
    private static final int MAX_NODES_PER_RUN = 50;

    private final ConversationLock lock;
    private final ConversationStateService stateService;
    private final ConversationRepository conversations;
    private final ConversationEventRepository events;
    private final MessageRepository messages;
    private final FlowRepository flows;
    private final FlowVersionRepository flowVersions;
    private final ChannelConnectionRepository channels;
    private final ContactService contacts;
    private final AesGcmEncryptor encryptor;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final Map<String, NodeExecutor> executors;
    private final Map<String, ChannelAdapter> adapters;

    public FlowEngine(ConversationLock lock,
                      ConversationStateService stateService,
                      ConversationRepository conversations,
                      ConversationEventRepository events,
                      MessageRepository messages,
                      FlowRepository flows,
                      FlowVersionRepository flowVersions,
                      ChannelConnectionRepository channels,
                      ContactService contacts,
                      AesGcmEncryptor encryptor,
                      JdbcTemplate jdbc,
                      TransactionTemplate txTemplate,
                      List<NodeExecutor> executorList,
                      List<ChannelAdapter> adapterList) {
        this.lock = lock;
        this.stateService = stateService;
        this.conversations = conversations;
        this.events = events;
        this.messages = messages;
        this.flows = flows;
        this.flowVersions = flowVersions;
        this.channels = channels;
        this.contacts = contacts;
        this.encryptor = encryptor;
        this.jdbc = jdbc;
        this.txTemplate = txTemplate;
        this.executors = new HashMap<>();
        for (NodeExecutor e : executorList) this.executors.put(e.supportedType(), e);
        this.adapters = new HashMap<>();
        for (ChannelAdapter a : adapterList) this.adapters.put(a.type().toDbValue(), a);
    }

    /**
     * Entry point called by the webhook controller for each verified inbound message.
     * Runs on a virtual thread — no synchronized blocks used anywhere in this path.
     */
    public void processInbound(UUID channelConnectionId, UUID tenantId, InboundMessage inbound) {
        ChannelConnection channel = channels.findByIdForWebhook(channelConnectionId)
            .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + channelConnectionId));

        if (channel.getFlowId() == null) {
            log.warn("Channel {} has no flow assigned — ignoring message", channelConnectionId);
            return;
        }

        // ContactService has its own @Transactional — works through the Spring proxy
        Contact contact = contacts.resolveOrCreate(inbound.identity(), tenantId,
            inbound.identity().externalId());

        // Find or create conversation in a short TX (TransactionTemplate avoids self-call problem)
        UUID conversationId = txTemplate.execute(status -> {
            RlsTenantInterceptor.applyToCurrentTransaction(jdbc);
            return conversations.findActiveByContactAndChannel(contact.getId(), channelConnectionId)
                .map(Conversation::getId)
                .orElseGet(() -> createConversation(contact.getId(), channelConnectionId,
                    tenantId, channel).getId());
        });

        // Acquire per-conversation Redis lock OUTSIDE the TX, then run the engine inside a new TX
        lock.executeWithLock(conversationId, () -> {
            txTemplate.execute(status -> {
                RlsTenantInterceptor.applyToCurrentTransaction(jdbc);
                runStep(conversationId, tenantId, channel, inbound);
                return null;
            });
            return null;
        });
    }

    // -----------------------------------------------------------------------
    // Core interpreter step (called inside TX + lock)
    // -----------------------------------------------------------------------

    private void runStep(UUID conversationId, UUID tenantId, ChannelConnection channel,
                          InboundMessage inbound) {
        Conversation conv = stateService.load(conversationId)
            .orElseThrow(() -> new IllegalStateException("Conversation not found: " + conversationId));

        FlowVersion version = flowVersions.findById(conv.getFlowVersionId())
            .orElseThrow(() -> new IllegalStateException("FlowVersion not found: " + conv.getFlowVersionId()));
        FlowGraph graph = version.getGraph();

        // Record inbound message
        saveMessage(conv, tenantId, "inbound", inbound.text(), inbound.channelMessageId(), null);

        // Snapshot the pre-run awaiting state so executors can inspect it correctly
        boolean wasAwaitingInput = "awaiting_input".equals(conv.getStatus());
        if (wasAwaitingInput) {
            conv.setStatus("active"); // transition back before processing
        }

        List<OutboundMessage> outbox = new ArrayList<>();
        int steps = 0;

        while (steps++ < MAX_NODES_PER_RUN) {
            String nodeId = conv.getCurrentNodeId();
            if (nodeId == null) { endConversation(conv); break; }

            FlowNode node = graph.findNode(nodeId);
            if (node == null) {
                log.error("Node {} not in graph for conversation {}", nodeId, conversationId);
                break;
            }

            NodeExecutor executor = executors.get(node.type());
            if (executor == null) {
                log.error("No executor for node type '{}', skipping", node.type());
                break;
            }

            NodeResult result = executor.execute(
                new NodeContext(nodeId, graph, inbound, conv, outbox, wasAwaitingInput));
            saveEvent(conv, tenantId, "node_executed", nodeId);

            if (result instanceof NodeResult.Advance) {
                String next = graph.nextNodeId(nodeId);
                conv.setCurrentNodeId(next);
                if (next == null) { endConversation(conv); break; }

            } else if (result instanceof NodeResult.Branch branch) {
                String next = graph.branchNodeId(nodeId, branch.edgeKey());
                saveEvent(conv, tenantId, "branched", nodeId);
                conv.setCurrentNodeId(next);
                if (next == null) { endConversation(conv); break; }

            } else if (result instanceof NodeResult.AwaitInput) {
                conv.setStatus("awaiting_input");
                saveEvent(conv, tenantId, "await_input", nodeId);
                break;

            } else if (result instanceof NodeResult.AwaitExternal) {
                conv.setStatus("waiting");
                saveEvent(conv, tenantId, "await_external", nodeId);
                break;

            } else if (result instanceof NodeResult.End) {
                endConversation(conv);
                saveEvent(conv, tenantId, "ended", nodeId);
                break;
            }
        }

        conv.setLastActivityAt(Instant.now());
        stateService.save(conv); // PG authoritative + Redis hot copy

        sendOutbound(outbox, channel, conv, tenantId);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Conversation createConversation(UUID contactId, UUID channelConnectionId,
                                             UUID tenantId, ChannelConnection channel) {
        Flow flow = flows.findByIdAndTenantId(channel.getFlowId(), tenantId)
            .orElseThrow(() -> new IllegalStateException("Flow not found: " + channel.getFlowId()));

        UUID publishedVersionId = flow.getPublishedVersionId();
        if (publishedVersionId == null) {
            throw new IllegalStateException("Flow has no published version: " + flow.getId());
        }

        FlowVersion version = flowVersions.findById(publishedVersionId)
            .orElseThrow(() -> new IllegalStateException("Published version missing: " + publishedVersionId));

        String startNodeId = version.getGraph().nodes().stream()
            .filter(n -> "trigger".equals(n.type()))
            .findFirst()
            .map(FlowNode::id)
            .orElseThrow(() -> new IllegalStateException("Flow graph has no trigger node"));

        var conv = new Conversation();
        conv.setTenantId(tenantId);
        conv.setContactId(contactId);
        conv.setChannelConnectionId(channelConnectionId);
        conv.setFlowId(flow.getId());
        conv.setFlowVersionId(publishedVersionId);
        conv.setCurrentNodeId(startNodeId);
        conv.setStatus("active");
        return conversations.save(conv);
    }

    private void endConversation(Conversation conv) {
        conv.setStatus("ended");
        conv.setEndedAt(Instant.now());
        stateService.evict(conv.getId());
    }

    private void saveEvent(Conversation conv, UUID tenantId, String type, String nodeId) {
        events.save(ConversationEvent.of(tenantId, conv.getId(), type, nodeId));
    }

    private void saveMessage(Conversation conv, UUID tenantId, String direction,
                              String text, String channelMessageId, String producedByNodeId) {
        var msg = new Message();
        msg.setTenantId(tenantId);
        msg.setConversationId(conv.getId());
        msg.setDirection(direction);
        msg.setContentType("text");
        msg.setBody(Map.of("text", text != null ? text : ""));
        msg.setChannelMessageId(channelMessageId);
        msg.setProducedByNodeId(producedByNodeId);
        messages.save(msg);
    }

    private void sendOutbound(List<OutboundMessage> outbox, ChannelConnection channel,
                               Conversation conv, UUID tenantId) {
        if (outbox.isEmpty()) return;

        ChannelAdapter adapter = adapters.get(channel.getType());
        if (adapter == null) {
            log.error("No adapter for channel type '{}' — cannot send {} message(s)",
                channel.getType(), outbox.size());
            return;
        }

        String botToken;
        try {
            botToken = encryptor.decryptToString(channel.getSecretCiphertext());
        } catch (Exception e) {
            log.error("Failed to decrypt bot token for channel {}", channel.getId(), e);
            return;
        }

        for (OutboundMessage outbound : outbox) {
            try {
                adapter.send(outbound, botToken);
                saveMessage(conv, tenantId, "outbound",
                    outbound.text(), null, outbound.producedByNodeId());
            } catch (Exception e) {
                log.error("Failed to send outbound message from node {} via {}",
                    outbound.producedByNodeId(), channel.getType(), e);
            }
        }
    }
}
