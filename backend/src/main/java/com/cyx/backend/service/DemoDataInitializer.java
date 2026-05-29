package com.cyx.backend.service;

import com.cyx.backend.entity.CalendarEventEntity;
import com.cyx.backend.entity.UserEntity;
import com.cyx.backend.repository.CalendarEventJpaRepository;
import com.cyx.backend.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoDataInitializer implements ApplicationRunner {
    private static final String DEMO_USERNAME = "demo";

    private final UserRepository userRepository;
    private final CalendarEventJpaRepository eventRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoDataInitializer(
            UserRepository userRepository,
            CalendarEventJpaRepository eventRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.eventRepository = eventRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UserEntity demoUser = userRepository.findByUsername(DEMO_USERNAME)
                .orElseGet(() -> userRepository.save(new UserEntity(
                        DEMO_USERNAME,
                        passwordEncoder.encode("123456"),
                        "演示用户"
                )));

        assignLegacyEventsToDemoUser(demoUser.getId());

        if (eventRepository.countByUserId(demoUser.getId()) > 0) {
            return;
        }

        LocalDate today = LocalDate.now();
        Instant now = Instant.now();
        eventRepository.saveAll(List.of(
                new CalendarEventEntity(
                        null,
                        demoUser.getId(),
                        "整理今日日程",
                        today.atTime(9, 0),
                        today.atTime(9, 30),
                        "语音日历原型",
                        "确认今天要完成的功能和接口",
                        "计划",
                        today.atTime(8, 50),
                        now,
                        now
                ),
                new CalendarEventEntity(
                        null,
                        demoUser.getId(),
                        "项目功能评审",
                        today.atTime(14, 30),
                        today.atTime(15, 30),
                        "线上会议",
                        "讨论日程 CRUD 和前端接入方式",
                        "会议",
                        today.atTime(14, 10),
                        now,
                        now
                ),
                new CalendarEventEntity(
                        null,
                        demoUser.getId(),
                        "准备演示脚本",
                        today.plusDays(2).atTime(16, 0),
                        today.plusDays(2).atTime(17, 0),
                        "答辩材料",
                        "梳理语音创建和查询日程的演示路径",
                        "演示",
                        null,
                        now,
                        now
                )
        ));
    }

    private void assignLegacyEventsToDemoUser(Long demoUserId) {
        List<CalendarEventEntity> legacyEvents = eventRepository.findByUserIdIsNull();

        if (legacyEvents.isEmpty()) {
            return;
        }

        List<CalendarEventEntity> migratedEvents = legacyEvents.stream()
                .map(event -> new CalendarEventEntity(
                        event.getId(),
                        demoUserId,
                        event.getTitle(),
                        event.getStartTime(),
                        event.getEndTime(),
                        event.getLocation(),
                        event.getDescription(),
                        event.getTag(),
                        event.getReminderTime(),
                        event.getCreatedAt(),
                        Instant.now()
                ))
                .toList();

        eventRepository.saveAll(migratedEvents);
    }
}
