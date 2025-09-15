package com.pushnotification.pushserver.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users",
    uniqueConstraints = {@UniqueConstraint(name = "users_name_key", columnNames = {"name"})},
    indexes = {@Index(name = "users_creation_ts", columnList = "creation_ts")}
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "creation_ts")
    private Long creationTs;

    @Column(name = "admin", nullable = false)
    private Short admin; // 0/1 smallint

    @Column(name = "upgrade_ts")
    private Long upgradeTs;

    @Column(name = "is_guest", nullable = false)
    private Short isGuest; // 0/1 smallint

    @Column(name = "appservice_id")
    private String appserviceId;

    @Column(name = "consent_version")
    private String consentVersion;

    @Column(name = "consent_server_notice_sent")
    private String consentServerNoticeSent;

    @Column(name = "user_type")
    private String userType;

    @Column(name = "deactivated", nullable = false)
    private Short deactivated; // 0/1 smallint

    @Column(name = "shadow_banned")
    private Boolean shadowBanned;

    @Column(name = "consent_ts")
    private Long consentTs;

    @Column(name = "approved")
    private Boolean approved;

    @Column(name = "locked", nullable = false)
    private Boolean locked;

    @Column(name = "suspended", nullable = false)
    private Boolean suspended;
}


