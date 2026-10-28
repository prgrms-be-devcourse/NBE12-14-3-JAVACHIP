package com.budzet.domain.notification

import com.budzet.domain.budget.entity.BudgetRequest
import com.budzet.domain.budget.entity.BudgetRequestType
import com.budzet.domain.budget.repository.BudgetRequestRepository
import com.budzet.domain.budget.service.BudgetRequestService
import com.budzet.domain.invite.entity.Invite
import com.budzet.domain.invite.repository.InviteRepository
import com.budzet.domain.invite.service.InviteService
import com.budzet.domain.notification.entity.Notification
import com.budzet.domain.notification.entity.NotificationType
import com.budzet.domain.notification.repository.NotificationRepository
import com.budzet.domain.notification.service.NotificationService
import com.budzet.domain.room.entity.*
import com.budzet.domain.room.entity.Currency
import com.budzet.domain.room.repository.RoomRepository
import com.budzet.domain.room.repository.UserRoomConnectionRepository
import com.budzet.domain.user.entity.User
import com.budzet.domain.user.repository.UserRepository
import com.budzet.domain.user.service.UserService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.*

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationIntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var notifications: NotificationRepository
    @Autowired lateinit var service: NotificationService
    @Autowired lateinit var requests: BudgetRequestRepository
    @Autowired lateinit var budgetService: BudgetRequestService
    @Autowired lateinit var inviteService: InviteService
    @Autowired lateinit var invites: InviteRepository
    @Autowired lateinit var rooms: RoomRepository
    @Autowired lateinit var members: UserRoomConnectionRepository
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var tx: TransactionTemplate
    private val userIds = mutableListOf<Long>()
    private val roomIds = mutableListOf<Long>()
    private lateinit var owner: User
    private lateinit var operator: User
    private lateinit var applicant: User
    private lateinit var ordinary: User
    private lateinit var outsider: User
    private lateinit var room: Room

    private fun user(name: String): User = userService.join("${UUID.randomUUID()}@test.com", "password123", name)
        .also { userIds.add(it.id!!) }
    private fun token(user: User) = userService.login(user.email,"password123").accessToken
    private fun register(actor: User = applicant, amount: Long = 12000): Long {
        budgetService.budgetRequestRegistration(room.id!!, actor, "팀 회의 식비", amount)
        return requests.findDtoByRoomId(room.id!!).maxBy { it.id!! }.id!!
    }
    @BeforeEach fun setup() {
        owner=user("방장"); operator=user("운영자"); applicant=user("신청자"); ordinary=user("일반멤버"); outsider=user("외부인")
        room=rooms.save(Room.create("알림 테스트",100000,Currency.KRW)).also { roomIds.add(it.id!!) }
        members.save(UserRoomConnection.createOwner(owner,room))
        val op=UserRoomConnection.createMember(operator,room); op.changeAuthority(Authority.OPERATOR); members.save(op)
        members.save(UserRoomConnection.createMember(applicant,room))
        members.save(UserRoomConnection.createMember(ordinary,room))
        val other=rooms.save(Room.create("다른 모임",100000,Currency.KRW)).also { roomIds.add(it.id!!) }
        members.save(UserRoomConnection.createOwner(outsider,other))
    }
    @AfterEach fun cleanup() {
        roomIds.forEach { if (rooms.existsById(it)) rooms.deleteById(it) }; roomIds.clear()
        userIds.forEach { if (users.existsById(it)) users.deleteById(it) }; userIds.clear()
    }
    @Test fun requestNotifiesOnlyManagersInSameRoom() {
        val id=register()
        assertEquals(1L,service.getUnreadCount(owner.id!!).count)
        assertEquals(1L,service.getUnreadCount(operator.id!!).count)
        for (user in listOf(applicant,ordinary,outsider)) assertEquals(0L,service.getUnreadCount(user.id!!).count)
        val item=service.getNotifications(owner.id!!).notifications.single()
        assertEquals(NotificationType.BUDGET_REQUESTED,item.type)
        assertEquals(id,item.targetId)
        assertEquals(room.name,item.roomName)
    }
    @Test fun managerDoesNotReceiveOwnRequestNotification() {
        register(owner)
        assertEquals(0L,service.getUnreadCount(owner.id!!).count)
        assertEquals(1L,service.getUnreadCount(operator.id!!).count)
    }
    @Test fun approvalNotifiesApplicantOnceAndRepeatFails() {
        val id=register()
        budgetService.approveBudgetRequest(room.id!!,owner,id)
        val notice=service.getNotifications(applicant.id!!).notifications.single()
        assertEquals(NotificationType.BUDGET_APPROVED,notice.type)
        assertThrows(RuntimeException::class.java) { budgetService.approveBudgetRequest(room.id!!,owner,id) }
        assertEquals(1L,service.getUnreadCount(applicant.id!!).count)
        assertEquals(88000L,rooms.findById(room.id!!).orElseThrow().availableBudget)
    }
    @Test fun rejectionNotifiesOnlyApplicant() {
        val id=register()
        budgetService.rejectBudgetRequest(room.id!!,operator,id,"사유 확인")
        assertEquals(NotificationType.BUDGET_REJECTED,service.getNotifications(applicant.id!!).notifications.single().type)
        assertEquals(0L,service.getUnreadCount(ordinary.id!!).count)
    }
    @Test fun rejectedActionProducesNoDecisionNotification() {
        val id=register()
        assertThrows(RuntimeException::class.java) { budgetService.approveBudgetRequest(room.id!!,ordinary,id) }
        assertEquals(0L,service.getUnreadCount(applicant.id!!).count)
        assertEquals(BudgetRequestType.REQUEST,requests.findDtoById(room.id!!,id)!!.status)
    }
    @Test fun joiningNotifiesManagersAndDuplicateJoinDoesNotDuplicateNotifications() {
        val code=UUID.randomUUID().toString().take(10)
        invites.save(Invite(code,room,LocalDateTime.now().plusHours(1)))
        inviteService.joinRoom(code,outsider.id!!)
        assertEquals(NotificationType.MEMBER_JOINED,service.getNotifications(owner.id!!).notifications.single().type)
        assertEquals(1L,service.getUnreadCount(operator.id!!).count)
        assertEquals(0L,service.getUnreadCount(ordinary.id!!).count)
        assertThrows(RuntimeException::class.java) { inviteService.joinRoom(code,outsider.id!!) }
        assertEquals(1L,service.getUnreadCount(owner.id!!).count)
    }
    @Test fun registrationAndNotificationsActuallyRollbackTogether() {
        val before=requests.findDtoByRoomId(room.id!!).size
        assertThrows(IllegalStateException::class.java) {
            tx.executeWithoutResult {
                budgetService.budgetRequestRegistration(room.id!!,applicant,"롤백 확인",12000)
                throw IllegalStateException("강제 롤백")
            }
        }
        assertEquals(before,requests.findDtoByRoomId(room.id!!).size)
        assertEquals(0L,service.getUnreadCount(owner.id!!).count)
        assertEquals(0L,service.getUnreadCount(operator.id!!).count)
    }
    @Test fun approvalAndNotificationActuallyRollbackTogether() {
        val id=register()
        assertThrows(IllegalStateException::class.java) {
            tx.executeWithoutResult {
                budgetService.approveBudgetRequest(room.id!!,owner,id)
                throw IllegalStateException("강제 롤백")
            }
        }
        assertEquals(BudgetRequestType.REQUEST,requests.findDtoById(room.id!!,id)!!.status)
        assertEquals(100000L,rooms.findById(room.id!!).orElseThrow().availableBudget)
        assertEquals(0L,service.getUnreadCount(applicant.id!!).count)
    }
    @Test fun readRequiresRecipientAndPreservesFirstReadTimestamp() {
        register()
        val item=service.getNotifications(owner.id!!).notifications.single()
        val own=token(owner)
        mvc.perform(patch("/notifications/${item.id}/read").header("Authorization","Bearer ${token(operator)}"))
            .andExpect(status().isNotFound)
        mvc.perform(patch("/notifications/${item.id}/read").header("Authorization","Bearer $own"))
            .andExpect(status().isNoContent)
        val first=service.getNotifications(owner.id!!).notifications.single().readAt
        assertNotNull(first)
        service.markAsRead(owner.id!!,item.id)
        assertEquals(first,service.getNotifications(owner.id!!).notifications.single().readAt)
        assertEquals(0L,service.getUnreadCount(owner.id!!).count)
        assertEquals(1L,service.getUnreadCount(operator.id!!).count)
    }
    @Test fun departedMemberCannotSeeOrReadOldRoomNotifications() {
        register()
        val item=service.getNotifications(operator.id!!).notifications.single()
        members.deleteById(UserRoomConnectionId(operator.id,room.id))
        assertTrue(service.getNotifications(operator.id!!).notifications.isEmpty())
        assertEquals(0L,service.getUnreadCount(operator.id!!).count)
        mvc.perform(patch("/notifications/${item.id}/read").header("Authorization","Bearer ${token(operator)}"))
            .andExpect(status().isForbidden)
    }
    @Test fun authenticatedApiDoesNotAcceptAnotherRecipientId() {
        register()
        mvc.perform(get("/notifications?userId=${owner.id}").header("Authorization","Bearer ${token(outsider)}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.notifications.length()").value(0))
        mvc.perform(get("/notifications/unread-count").header("Authorization","Bearer ${token(owner)}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.count").value(1))
    }
    @Test fun unauthenticatedApiRequestsAreRejected() {
        mvc.perform(get("/notifications")).andExpect(status().isUnauthorized)
        mvc.perform(get("/notifications/unread-count")).andExpect(status().isUnauthorized)
        mvc.perform(patch("/notifications/1/read")).andExpect(status().isUnauthorized)
    }
    @Test fun pageSizeIsBoundedAndPaginationIsStable() {
        tx.executeWithoutResult {
            notifications.saveAll((1L..25L).map { Notification(owner,room,NotificationType.MEMBER_JOINED,"멤버 참여 $it",room.id!!) })
        }
        val first=service.getNotifications(owner.id!!,0,20)
        val second=service.getNotifications(owner.id!!,first.nextPage!!,20)
        assertEquals(20,first.notifications.size)
        assertEquals(5,second.notifications.size)
        assertNull(second.nextPage)
        assertTrue(first.notifications.zipWithNext().all { (a,b) -> a.id > b.id })
        assertTrue(first.notifications.map { it.id }.intersect(second.notifications.map { it.id }.toSet()).isEmpty())
        val auth=token(owner)
        mvc.perform(get("/notifications?size=51").header("Authorization","Bearer $auth")).andExpect(status().isBadRequest)
        mvc.perform(get("/notifications?page=-1").header("Authorization","Bearer $auth")).andExpect(status().isBadRequest)
    }
    @Test fun notificationsCannotBeCreatedOutsideBusinessTransaction() {
        val request=BudgetRequest(room,applicant,"잘못된 호출",1000)
        assertThrows(IllegalTransactionStateException::class.java) { service.notifyBudgetRequested(request) }
    }
    @Test fun blankMessagesAndInvalidTargetsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Notification(owner,room,NotificationType.MEMBER_JOINED," ",1) }
        assertThrows(IllegalArgumentException::class.java) { Notification(owner,room,NotificationType.MEMBER_JOINED,"참여",0) }
    }
}
