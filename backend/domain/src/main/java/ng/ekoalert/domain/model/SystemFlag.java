package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A single mutable switch. The one that matters is alerts_enabled. */
@Entity
@Table(name = "system_flag")
public class SystemFlag {

    public static final String ALERTS_ENABLED = "alerts_enabled";

    @Id
    @Column(name = "key")
    private String key;

    @Column(name = "value", nullable = false)
    private String value;

    protected SystemFlag() {
    }

    public SystemFlag(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
