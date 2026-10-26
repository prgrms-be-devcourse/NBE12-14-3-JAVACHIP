package com.budzet.domain.invite.controller;

import com.budzet.domain.invite.entity.Invite;
import com.budzet.domain.invite.repository.InviteRepository;
import com.budzet.domain.room.entity.Authority;
import com.budzet.domain.room.entity.Currency;
import com.budzet.domain.room.entity.Room;
import com.budzet.domain.room.entity.UserRoomConnection;
import com.budzet.domain.room.entity.UserRoomConnectionId;
import com.budzet.domain.room.repository.RoomRepository;
import com.budzet.domain.room.repository.UserRoomConnectionRepository;
import com.budzet.domain.user.entity.User;
import com.budzet.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InviteAuthorizationIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private RoomRepository roomRepository;
    @Autowired private UserRoomConnectionRepository membershipRepository;
    @Autowired private InviteRepository inviteRepository;

    private Room room;
    private User owner;
    private User operator;
    private User member;
    private User outsider;
    private String ownerToken;
    private String operatorToken;
    private String memberToken;
    private String outsiderToken;
    private String inviteCode;

    @BeforeEach
    void setUp() {
        owner = createUser("owner");
        operator = createUser("operator");
        member = createUser("member");
        outsider = createUser("outsider");
        room = roomRepository.save(Room.create("권한 테스트", 10000L, Currency.KRW));
        membershipRepository.save(UserRoomConnection.createOwner(owner, room));
        UserRoomConnection operatorConnection = UserRoomConnection.createMember(operator, room);
        operatorConnection.changeAuthority(Authority.OPERATOR);
        membershipRepository.save(operatorConnection);
        membershipRepository.save(UserRoomConnection.createMember(member, room));
        Room otherRoom = roomRepository.save(Room.create("다른 모임", 1000L, Currency.KRW));
        membershipRepository.save(UserRoomConnection.createOwner(outsider, otherRoom));
        ownerToken = login(owner);
        operatorToken = login(operator);
        memberToken = login(member);
        outsiderToken = login(outsider);
        inviteCode = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        inviteRepository.saveAndFlush(new Invite(inviteCode, room, LocalDateTime.now().plusHours(1)));
    }

    private User createUser(String name) {
        return userService.join(UUID.randomUUID() + "@test.com", "password123", name);
    }

    private String login(User user) {
        return userService.login(user.getEmail(), "password123").accessToken();
    }

    private String endpoint(String resource) {
        return "/rooms/" + room.getId() + "/" + resource.replace("{userId}", member.getId().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "operator", "outsider"})
    @DisplayName("인증된 비OWNER의 초대 생성은 403을 반환하고 초대를 저장하지 않는다")
    void nonOwnerCannotCreateInvite(String role) throws Exception {
        String token = switch (role) {
            case "operator" -> operatorToken;
            case "outsider" -> outsiderToken;
            default -> memberToken;
        };
        long count = inviteRepository.count();
        mockMvc.perform(post(endpoint("invites")).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value(403))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(count, inviteRepository.count());
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists());
    }

    @Test
    @DisplayName("인증되지 않은 초대 생성은 401을 반환한다")
    void anonymousCannotCreateInvite() throws Exception {
        long count = inviteRepository.count();
        mockMvc.perform(post(endpoint("invites"))).andExpect(status().isUnauthorized());
        assertEquals(count, inviteRepository.count());
    }

    @Test
    @DisplayName("OWNER는 초대 코드를 생성한다")
    void ownerCanCreateInvite() throws Exception {
        long count = inviteRepository.count();
        mockMvc.perform(post(endpoint("invites")).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.data.code").isNotEmpty());
        assertEquals(count + 1, inviteRepository.count());
    }

    @Test
    @DisplayName("OWNER는 자신의 모임 초대 코드를 조회한다")
    void ownerCanReadInvites() throws Exception {
        mockMvc.perform(get(endpoint("invites")).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value(inviteCode));
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "operator"})
    @DisplayName("모임 멤버여도 OWNER가 아니면 초대 코드를 조회할 수 없다")
    void nonOwnerCannotReadInvites(String role) throws Exception {
        String token = role.equals("member") ? memberToken : operatorToken;
        mockMvc.perform(get(endpoint("invites")).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invites", "members", "members/{userId}/authority"})
    @DisplayName("다른 모임 OWNER도 대상 모임의 초대·멤버·권한을 조회할 수 없다")
    void outsiderCannotReadRoomInformation(String resource) throws Exception {
        mockMvc.perform(get(endpoint(resource)).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.resultCode").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertFalse(membershipRepository.existsById(new UserRoomConnectionId(outsider.getId(), room.getId())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invites", "members", "members/{userId}/authority"})
    @DisplayName("비로그인 사용자는 모임 조회 API를 사용할 수 없다")
    void anonymousCannotReadRoomInformation(String resource) throws Exception {
        mockMvc.perform(get(endpoint(resource)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "operator", "member"})
    @DisplayName("모임 소속 사용자는 멤버 목록과 다른 멤버의 권한을 조회한다")
    void membersCanReadMembersAndAuthority(String role) throws Exception {
        String token = switch (role) {
            case "owner" -> ownerToken;
            case "operator" -> operatorToken;
            default -> memberToken;
        };
        mockMvc.perform(get(endpoint("members")).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[*].userId", org.hamcrest.Matchers.hasItem(member.getId().intValue())));
        mockMvc.perform(get(endpoint("members/{userId}/authority")).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("MEMBER"));
    }

    @Test
    @DisplayName("유효한 코드를 전달받은 비멤버는 MEMBER로 가입할 수 있다")
    void invitedOutsiderCanJoin() throws Exception {
        mockMvc.perform(post("/rooms/join/{code}", inviteCode)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.data.roomId").value(room.getId()))
                .andExpect(jsonPath("$.data.authority").value("MEMBER"));
        assertEquals(Authority.MEMBER, membershipRepository
                .findByUser_IdAndRoom_Id(outsider.getId(), room.getId()).orElseThrow().getAuthority());
    }

    @Test
    @DisplayName("만료된 초대로는 멤버 연결이 생성되지 않는다")
    void expiredInviteCannotJoin() throws Exception {
        String expiredCode = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        inviteRepository.saveAndFlush(new Invite(expiredCode, room, LocalDateTime.now().minusMinutes(1)));
        mockMvc.perform(post("/rooms/join/{code}", expiredCode)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
        assertFalse(membershipRepository.existsById(new UserRoomConnectionId(outsider.getId(), room.getId())));
    }

    @Test
    @DisplayName("이미 가입한 멤버는 같은 초대로 중복 가입할 수 없다")
    void existingMemberCannotJoinAgain() throws Exception {
        mockMvc.perform(post("/rooms/join/{code}", inviteCode)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isConflict());
        assertEquals(3, membershipRepository.findAllByRoom_Id(room.getId()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"leave", "kick"})
    @DisplayName("탈퇴·강퇴된 멤버는 기존 JWT로도 모임 정보를 조회할 수 없다")
    void removedMemberLosesReadAccess(String action) throws Exception {
        if (action.equals("leave")) {
            mockMvc.perform(delete(endpoint("members/me")).header("Authorization", "Bearer " + memberToken))
                    .andExpect(status().isOk());
        } else {
            mockMvc.perform(delete(endpoint("members/{userId}"))
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk());
        }
        membershipRepository.flush();
        for (String resource : new String[]{"invites", "members", "members/" + owner.getId() + "/authority"}) {
            mockMvc.perform(get(endpoint(resource)).header("Authorization", "Bearer " + memberToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "operator", "outsider"})
    @DisplayName("OWNER가 아닌 사용자의 권한 변경은 거부되고 대상 권한이 유지된다")
    void nonOwnerCannotChangeAuthority(String role) throws Exception {
        String token = switch (role) {
            case "operator" -> operatorToken;
            case "outsider" -> outsiderToken;
            default -> memberToken;
        };
        mockMvc.perform(patch(endpoint("members/{userId}/authority"))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"authority\":\"OPERATOR\"}"))
                .andExpect(status().is(role.equals("outsider") ? 404 : 403));
        assertEquals(Authority.MEMBER, membershipRepository
                .findByUser_IdAndRoom_Id(member.getId(), room.getId()).orElseThrow().getAuthority());
    }

    @Test
    @DisplayName("OWNER는 운영자를 지정·해제할 수 있지만 일반 변경으로 OWNER를 지정할 수 없다")
    void ownerCanManageOperatorButCannotAssignOwner() throws Exception {
        for (String role : new String[]{"OPERATOR", "MEMBER"}) {
            mockMvc.perform(patch(endpoint("members/{userId}/authority"))
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"authority\":\"" + role + "\"}"))
                    .andExpect(status().isOk());
            assertEquals(Authority.valueOf(role), membershipRepository
                    .findByUser_IdAndRoom_Id(member.getId(), room.getId()).orElseThrow().getAuthority());
        }
        mockMvc.perform(patch(endpoint("members/{userId}/authority"))
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"authority\":\"OWNER\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(Authority.MEMBER, membershipRepository
                .findByUser_IdAndRoom_Id(member.getId(), room.getId()).orElseThrow().getAuthority());
    }
}
