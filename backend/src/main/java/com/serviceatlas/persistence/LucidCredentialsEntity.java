package com.serviceatlas.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Encrypted Lucid OAuth tokens for one workspace (FR-6.2).
 *
 * <p>Only ever populated when the user explicitly connects a Lucid account. The token blob is
 * encrypted before it reaches this entity; nothing here is readable from a raw database dump, and
 * the encryption key comes from the environment, never the repository (Q-3).
 */
@Entity
@Table(name = "lucid_credentials")
public class LucidCredentialsEntity {

    @Id
    @Column(name = "workspace_id")
    private Long workspaceId;

    @Column(name = "encrypted_tokens", nullable = false, length = 4000)
    private String encryptedTokens;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    protected LucidCredentialsEntity() {
    }

    public LucidCredentialsEntity(Long workspaceId, String encryptedTokens) {
        this.workspaceId = workspaceId;
        this.encryptedTokens = encryptedTokens;
        this.connectedAt = Instant.now();
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public String getEncryptedTokens() {
        return encryptedTokens;
    }

    public void setEncryptedTokens(String encryptedTokens) {
        this.encryptedTokens = encryptedTokens;
        this.connectedAt = Instant.now();
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }
}
