package com.iacross.flowpilot.engine.repository;

import com.iacross.flowpilot.engine.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
}
