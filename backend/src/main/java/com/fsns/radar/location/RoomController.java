package com.fsns.radar.location;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/location")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    public record EnterRequest(@NotNull Double latitude, @NotNull Double longitude) {}

    /** 설계서 4.2 — 방 입장. 응답에 타인 BLE 토큰 목록은 포함되지 않는다. */
    @PostMapping("/room/enter")
    public Map<String, Object> enter(Authentication auth, @Valid @RequestBody EnterRequest req) {
        Long userId = (Long) auth.getPrincipal();
        return roomService.enter(userId, req.latitude(), req.longitude());
    }
}
