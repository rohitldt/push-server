package com.pushnotification.pushserver.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "rooms", indexes = {@Index(name = "public_room_index", columnList = "is_public")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Room {

    @Id
    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Column(name = "is_public")
    private Boolean isPublic;

    @Column(name = "creator")
    private String creator;

    @Column(name = "room_version")
    private String roomVersion;

    @Column(name = "has_auth_chain_index")
    private Boolean hasAuthChainIndex;
}


