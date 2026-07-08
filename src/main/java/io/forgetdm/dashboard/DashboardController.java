package io.forgetdm.dashboard;

import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.discovery.ClassificationRepository;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.provision.ProvisionJobRepository;
import io.forgetdm.reservation.ReservationRepository;
import io.forgetdm.validation.ValidationReportRepository;
import io.forgetdm.virtualization.VirtualDatabaseRepository;
import io.forgetdm.virtualization.VirtualSnapshotRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DataSourceRepository ds;
    private final ClassificationRepository cls;
    private final MaskingPolicyRepository pol;
    private final ProvisionJobRepository jobs;
    private final ReservationRepository res;
    private final ValidationReportRepository val;
    private final VirtualSnapshotRepository snaps;
    private final VirtualDatabaseRepository vdbs;

    public DashboardController(DataSourceRepository ds, ClassificationRepository cls, MaskingPolicyRepository pol,
                               ProvisionJobRepository jobs, ReservationRepository res, ValidationReportRepository val,
                               VirtualSnapshotRepository snaps, VirtualDatabaseRepository vdbs) {
        this.ds = ds; this.cls = cls; this.pol = pol; this.jobs = jobs; this.res = res; this.val = val;
        this.snaps = snaps; this.vdbs = vdbs;
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("dataSources", ds.count());
        m.put("classifications", cls.count());
        m.put("policies", pol.count());
        m.put("jobs", jobs.count());
        m.put("virtualSnapshots", snaps.count());
        m.put("vdbs", vdbs.count());
        m.put("reservations", res.count());
        m.put("validationReports", val.count());
        return m;
    }
}
