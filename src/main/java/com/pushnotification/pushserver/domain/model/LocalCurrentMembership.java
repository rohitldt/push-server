package com.pushnotification.pushserver.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Entity
@Table(name = "local_current_membership", indexes = {
        @Index(name = "local_current_membership_room_id", columnList = "room_id"),
        @Index(name = "local_current_membership_user_id", columnList = "user_id")
})
@IdClass(LocalCurrentMembership.Pk.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocalCurrentMembership {

    @Id
    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Id
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "membership", nullable = false)
    private String membership; // join, leave, invite, ban

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Pk implements Serializable {
        private String roomId;
        private String userId;
    }
}


