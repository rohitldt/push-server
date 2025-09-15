package com.pushnotification.pushserver.domain.repository;

import com.pushnotification.pushserver.domain.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, String> {
}


