package com.aistar.backend.domain.usage.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "plan")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "plan_id")
    private Long id;

    @Column(name = "name", nullable = false, length = 20)
    private String name;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @Column(name = "max_project", nullable = false)
    private Integer maxProject;

    @Column(name = "max_depth", nullable = false)
    private Integer maxDepth;

    @Column(name = "max_file_size", nullable = false)
    private Long maxFileSize;

    @Column(name = "ai_limit", nullable = false)
    private Long aiLimit;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
