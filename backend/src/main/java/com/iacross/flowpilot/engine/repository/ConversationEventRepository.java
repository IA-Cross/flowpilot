package com.iacross.flowpilot.engine.repository;

import com.iacross.flowpilot.engine.domain.ConversationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConversationEventRepository extends JpaRepository<ConversationEvent, UUID> {
}
