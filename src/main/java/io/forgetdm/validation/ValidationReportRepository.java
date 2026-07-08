package io.forgetdm.validation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationReportRepository extends JpaRepository<ValidationReportEntity, Long> {
}
