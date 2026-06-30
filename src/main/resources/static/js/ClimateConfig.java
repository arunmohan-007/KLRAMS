package com.fist.rmms_backend.climate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Editable climate config stored as key/value JSON (mirrors site_content).
 * Keys: layers, class_rfi, class_sli, class_fsi, cvi, cvi_bands.
 */
@Entity
@Table(name = "climate_config")
public class ClimateConfig {

    @Id
    @Column(name = "ckey", length = 64)
    private String ckey;

    @Column(name = "cvalue", columnDefinition = "text", nullable = false)
    private String cvalue;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ClimateConfig() { }

    public ClimateConfig(String ckey, String cvalue) {
        this.ckey = ckey;
        this.cvalue = cvalue;
        this.updatedAt = LocalDateTime.now();
    }

    public String getCkey() { return ckey; }
    public void setCkey(String ckey) { this.ckey = ckey; }

    public String getCvalue() { return cvalue; }
    public void setCvalue(String cvalue) { this.cvalue = cvalue; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
