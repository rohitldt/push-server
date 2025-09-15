package com.pushnotification.pushserver.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "pushers",
    uniqueConstraints = {@UniqueConstraint(name = "pushers2_app_id_pushkey_user_name_key", columnNames = {"app_id", "pushkey", "user_name"})}
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Pusher {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Transient
    private String accessToken; // Ignored; not needed for sending




    @Column(name = "profile_tag", nullable = false)
    private String profileTag;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "app_id", nullable = false)
    private String appId;

    @Column(name = "app_display_name", nullable = false)
    private String appDisplayName;

    @Column(name = "device_display_name", nullable = false)
    private String deviceDisplayName;

    @Column(name = "pushkey", nullable = false)
    private String pushkey;

    @Column(name = "ts", nullable = false)
    private Long ts;

    @Column(name = "lang")
    private String lang;

    @Transient
    private String data; // Ignored; can be large JSON and not required

    @Column(name = "last_stream_ordering")
    private Long lastStreamOrdering;

    @Column(name = "last_success")
    private Long lastSuccess;

    @Column(name = "failing_since")
    private Long failingSince;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "instance_name")
    private String instanceName;
}


