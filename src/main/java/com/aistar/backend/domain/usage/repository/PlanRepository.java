package com.aistar.backend.domain.usage.repository;

import com.aistar.backend.domain.usage.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {
}
