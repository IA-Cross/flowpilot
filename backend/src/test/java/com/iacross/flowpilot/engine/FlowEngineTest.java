package com.iacross.flowpilot.engine;

import com.iacross.flowpilot.channel.contact.Contact;
import com.iacross.flowpilot.channel.contact.ContactService;
import com.iacross.flowpilot.channel.domain.ChannelConnection;
import com.iacross.flowpilot.channel.repository.ChannelConnectionRepository;
import com.iacross.flowpilot.channel.spi.*;
import com.iacross.flowpilot.channel.telegram.TelegramChannelAdapter;
import com.iacross.flowpilot.engine.domain.Conversation;
import com.iacross.flowpilot.engine.executor.*;
import com.iacross.flowpilot.engine.repository.*;
import com.iacross.flowpilot.engine.service.ConversationStateService;
import com.iacross.flowpilot.engine.service.FlowEngine;
import com.iacross.flowpilot.flow.domain.*;
import com.iacross.flowpilot.flow.repository.FlowRepository;
import com.iacross.flowpilot.flow.repository.FlowVersionRepository;
import com.iacross.flowpilot.shared.lock.ConversationLock;
import com.iacross.flowpilot.shared.security.AesGcmEncryptor;
import com.iacross.flowpilot.shared.security.EncryptionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for the FlowEngine interpreter loop.
 *
 * All collaborators are mocked. Verifies:
 *  - Linear flow runs trigger → send_message → collect_input (yields AwaitInput)
 *  - On second call (with user reply), collect_input advances and conversation ends
 *  - State transitions: active → awaiting_input → ended
 *  - Outbound messages are sent via the adapter
 */
class FlowEngineTest {

    @Mock ConversationLock lock;
    @Mock ConversationStateService stateService;
    @Mock ConversationRepository conversationRepo;
    @Mock ConversationEventRepository eventRepo;
    @Mock MessageRepository messageRepo;
    @Mock FlowRepository flowRepo;
    @Mock FlowVersionRepository flowVersionRepo;
    @Mock ChannelConnectionRepository channelRepo;
    @Mock ContactService contactService;
    @Mock TelegramChannelAdapter telegramAdapter;
    @Mock JdbcTemplate jdbc;
    @Mock TransactionTemplate txTemplate;

    private FlowEngine engine;
    private AesGcmEncryptor encryptor;
    private static final String MASTER_KEY = "0".repeat(64);

    // Shared test objects
    private UUID tenantId;
    private UUID channelConnectionId;
    private UUID contactId;
    private UUID conversationId;
    private ChannelConnection channelConn;
    private FlowGraph graph;
    private FlowVersion flowVersion;
    private com.iacross.flowpilot.flow.domain.Flow flow;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        encryptor = new AesGcmEncryptor(new EncryptionProperties(MASTER_KEY));
        tenantId          = UUID.randomUUID();
        channelConnectionId = UUID.randomUUID();
        contactId         = UUID.randomUUID();
        conversationId    = UUID.randomUUID();

        // TransactionTemplate: execute the supplier directly (no real TX)
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            var cb = inv.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0);
            try { return cb.doInTransaction(null); } catch (Exception e) { throw new RuntimeException(e); }
        });

        // Lock: run the supplier directly
        when(lock.executeWithLock(any(), any())).thenAnswer(inv -> {
            Supplier<?> action = inv.getArgument(1);
            return action.get();
        });

        // ContactService
        var contact = new Contact();
        contact.setId(contactId);
        when(contactService.resolveOrCreate(any(), any(), any())).thenReturn(contact);

        // Channel connection
        channelConn = new ChannelConnection();
        channelConn.setId(channelConnectionId);
        channelConn.setTenantId(tenantId);
        channelConn.setType("telegram");
        channelConn.setFlowId(UUID.randomUUID());
        channelConn.setSecretCiphertext(encryptor.encrypt("bot-token"));
        when(channelRepo.findByIdForWebhook(channelConnectionId)).thenReturn(Optional.of(channelConn));

        // Flow graph: trigger → send_message("Hello!") → collect_input(name)
        var triggerNode  = new FlowNode("n1", "trigger",      Map.of(), Map.of());
        var sendNode     = new FlowNode("n2", "send_message",  Map.of("text", "Hello!"), Map.of());
        var collectNode  = new FlowNode("n3", "collect_input", Map.of("variableName", "name"), Map.of());
        var e1 = new FlowEdge("e1", "n1", "n2", null);
        var e2 = new FlowEdge("e2", "n2", "n3", null);
        graph = new FlowGraph(List.of(triggerNode, sendNode, collectNode), List.of(e1, e2));

        flowVersion = new FlowVersion();
        flowVersion.setId(UUID.randomUUID());
        flowVersion.setGraph(graph);
        when(flowVersionRepo.findById(flowVersion.getId())).thenReturn(Optional.of(flowVersion));

        flow = new com.iacross.flowpilot.flow.domain.Flow();
        flow.setId(channelConn.getFlowId());
        flow.setPublishedVersionId(flowVersion.getId());
        when(flowRepo.findByIdAndTenantId(channelConn.getFlowId(), tenantId)).thenReturn(Optional.of(flow));

        // No active conversation initially → engine will create one
        when(conversationRepo.findActiveByContactAndChannel(contactId, channelConnectionId))
            .thenReturn(Optional.empty());

        // Conversation creation
        when(conversationRepo.save(any())).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            if (c.getId() == null) c.setId(conversationId);
            return c;
        });

        // State service load: return a fresh conversation on first load
        when(stateService.load(conversationId)).thenAnswer(inv -> {
            var conv = new Conversation();
            conv.setId(conversationId);
            conv.setTenantId(tenantId);
            conv.setContactId(contactId);
            conv.setFlowVersionId(flowVersion.getId());
            conv.setCurrentNodeId("n1");
            conv.setStatus("active");
            conv.setVariables(new HashMap<>());
            return Optional.of(conv);
        });
        when(stateService.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // JdbcTemplate: stub the RLS SET LOCAL call (execute returns void)
        doNothing().when(jdbc).execute(anyString());

        // Telegram adapter
        when(telegramAdapter.type()).thenReturn(ChannelType.TELEGRAM);

        engine = new FlowEngine(lock, stateService, conversationRepo, eventRepo, messageRepo,
            flowRepo, flowVersionRepo, channelRepo, contactService, encryptor, jdbc, txTemplate,
            List.of(new TriggerNodeExecutor(), new SendMessageNodeExecutor(),
                     new CollectInputNodeExecutor(), new ConditionNodeExecutor()),
            List.of(telegramAdapter));
    }

    @Test
    void linearFlow_runsToAwaitInput_onFirstMessage() {
        var inbound = inbound("start");

        engine.processInbound(channelConnectionId, tenantId, inbound);

        // Engine should have sent the greeting and stopped at collect_input
        verify(telegramAdapter).send(argThat(m -> m.text().equals("Hello!")), anyString());
        verify(stateService).save(argThat(c -> "awaiting_input".equals(c.getStatus())));
    }

    @Test
    void secondMessage_collectsInputAndAdvancesToEnd() {
        // Pre-configure: conversation is already at collect_input, awaiting_input
        when(conversationRepo.findActiveByContactAndChannel(contactId, channelConnectionId))
            .thenReturn(Optional.of(convo("awaiting_input", "n3")));
        when(stateService.load(conversationId)).thenAnswer(inv -> Optional.of(convo("awaiting_input", "n3")));

        engine.processInbound(channelConnectionId, tenantId, inbound("Alice"));

        // After storing name, graph has no more nodes → conversation ends
        verify(stateService).save(argThat(c -> "ended".equals(c.getStatus())));
    }

    @Test
    void outboundMessage_hasTelegramRecipient() {
        var inbound = inbound("hello");
        engine.processInbound(channelConnectionId, tenantId, inbound);

        verify(telegramAdapter).send(
            argThat(m -> "tg-123".equals(m.recipient().externalId())), anyString());
    }

    // -----------------------------------------------------------------------

    private InboundMessage inbound(String text) {
        return new InboundMessage(new ChannelIdentity(ChannelType.TELEGRAM, "tg-123"), text, "msg-1");
    }

    private Conversation convo(String status, String nodeId) {
        var c = new Conversation();
        c.setId(conversationId);
        c.setTenantId(tenantId);
        c.setContactId(contactId);
        c.setFlowVersionId(flowVersion.getId());
        c.setChannelConnectionId(channelConnectionId);
        c.setFlowId(flow.getId());
        c.setCurrentNodeId(nodeId);
        c.setStatus(status);
        c.setVariables(new HashMap<>());
        return c;
    }
}
