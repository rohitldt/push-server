package com.pushnotification.pushserver.domain.repository;

import com.pushnotification.pushserver.domain.model.LocalCurrentMembership;
import com.pushnotification.pushserver.domain.model.LocalCurrentMembership.Pk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocalCurrentMembershipRepository extends JpaRepository<LocalCurrentMembership, Pk> {
    List<LocalCurrentMembership> findByRoomIdAndMembership(String roomId, String membership);
}


