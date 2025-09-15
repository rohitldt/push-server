package com.pushnotification.pushserver.domain.repository;

import com.pushnotification.pushserver.domain.model.Pusher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PusherRepository extends JpaRepository<Pusher, Long> {
    Optional<Pusher> findByAppIdAndPushkeyAndUserName(String appId, String pushkey, String userName);
    List<Pusher> findByUserName(String userName);
    List<Pusher> findByUserNameIn(List<String> userNames);
}


