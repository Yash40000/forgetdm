package io.forgetdm.cdc;

import io.forgetdm.config.ForgeProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Continuous CDC capture. When {@code forgetdm.cdc.continuous-enabled=true}, this sweeps every active
 * capture on a fixed interval and polls it, so each slot's confirmed position stays close to the
 * current log position. That keeps decoding cheap and stops idle slots from pinning WAL (the exact
 * failure mode seen during point-in-time testing). Set continuous-enabled=false for manual-only sites.
 */
@Component
public class CdcPollScheduler {

    private static final Logger log = LoggerFactory.getLogger(CdcPollScheduler.class);

    private final ForgeProps props;
    private final CdcService cdc;

    public CdcPollScheduler(ForgeProps props, CdcService cdc) {
        this.props = props;
        this.cdc = cdc;
    }

    @Scheduled(fixedDelayString = "${forgetdm.cdc.interval-ms:30000}",
               initialDelayString = "${forgetdm.cdc.interval-ms:30000}")
    public void sweep() {
        if (!props.getCdc().isContinuousEnabled()) return;
        try {
            int polled = cdc.pollAllActive();
            if (polled > 0) log.debug("CDC continuous poll swept {} active capture(s)", polled);
        } catch (Exception e) {
            log.warn("CDC continuous poll sweep failed: {}", e.getMessage());
        }
    }
}
