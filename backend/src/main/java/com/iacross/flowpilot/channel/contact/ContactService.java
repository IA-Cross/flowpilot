package com.iacross.flowpilot.channel.contact;

import com.iacross.flowpilot.channel.spi.ChannelIdentity;
import com.iacross.flowpilot.shared.tenant.RlsTenantInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Resolves or creates a Contact from an inbound ChannelIdentity.
 * Deduplication key: (tenant_id, channel, external_id) — unique in DB.
 */
@Service
@Transactional
public class ContactService {

    private final ContactRepository contacts;
    private final ContactIdentityRepository identities;
    private final JdbcTemplate jdbc;

    public ContactService(ContactRepository contacts,
                          ContactIdentityRepository identities,
                          JdbcTemplate jdbc) {
        this.contacts = contacts;
        this.identities = identities;
        this.jdbc = jdbc;
    }

    public Contact resolveOrCreate(ChannelIdentity identity, UUID tenantId, String displayName) {
        RlsTenantInterceptor.applyToCurrentTransaction(jdbc);

        String channelDb = identity.channel().toDbValue();

        return identities
            .findByTenantChannelExternal(tenantId, channelDb, identity.externalId())
            .map(ci -> {
                Contact c = contacts.findById(ci.getContactId())
                    .orElseThrow(() -> new IllegalStateException("Orphaned contact_identity: " + ci.getId()));
                c.setLastSeenAt(Instant.now());
                return contacts.save(c);
            })
            .orElseGet(() -> {
                var contact = new Contact();
                contact.setTenantId(tenantId);
                contact.setDisplayName(displayName);
                contact.setLastSeenAt(Instant.now());
                contacts.save(contact);

                var ci = new ContactIdentity();
                ci.setTenantId(tenantId);
                ci.setContactId(contact.getId());
                ci.setChannel(channelDb);
                ci.setExternalId(identity.externalId());
                identities.save(ci);

                return contact;
            });
    }
}
