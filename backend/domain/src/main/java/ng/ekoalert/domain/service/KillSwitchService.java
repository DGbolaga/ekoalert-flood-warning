package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.SystemFlag;
import ng.ekoalert.domain.repo.SystemFlagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The off button.
 *
 * <p>When alerts are disabled, propagation still runs and alert rows are still
 * written, but every row is marked suppressed and nothing is delivered. Halting
 * the pipeline instead would leave no record of what the system would have said,
 * and the record is the point.
 */
@Service
public class KillSwitchService {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchService.class);

    private final SystemFlagRepository flags;

    public KillSwitchService(SystemFlagRepository flags) {
        this.flags = flags;
    }

    @Transactional(readOnly = true)
    public boolean alertsEnabled() {
        return flags.findById(SystemFlag.ALERTS_ENABLED)
                .map(flag -> Boolean.parseBoolean(flag.getValue()))
                // A missing flag means something is wrong with the database, and
                // the safe reading of "I do not know" is to send nothing.
                .orElse(false);
    }

    @Transactional
    public void setAlertsEnabled(boolean enabled) {
        SystemFlag flag = flags.findById(SystemFlag.ALERTS_ENABLED)
                .orElseGet(() -> new SystemFlag(SystemFlag.ALERTS_ENABLED, "false"));
        flag.setValue(Boolean.toString(enabled));
        flags.save(flag);
        log.warn("kill switch: outgoing alerts are now {}", enabled ? "ENABLED" : "DISABLED");
    }
}
