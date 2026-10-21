package rikka.shizuku.server.util

import android.content.pm.UserInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.hidden.compat.UserManagerApis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UsersCompatTest {

    private lateinit var userManagerMock: MockedStatic<UserManagerApis>

    @Before
    fun setUp() {
        userManagerMock = Mockito.mockStatic(UserManagerApis::class.java)
    }

    @After
    fun tearDown() {
        userManagerMock.close()
    }

    private fun makeUsers(vararg ids: Int): List<UserInfo> =
        ids.map { id ->
            UserInfo().apply {
                this.id = id
                name = "user-$id"
                flags = 0
            }
        }

    @Test
    fun `getUsers uses 3-boolean overload when supported by platform`() {
        userManagerMock.`when`<List<UserInfo>> {
            UserManagerApis.getUsers(true, true, true)
        }.thenReturn(makeUsers(0, 10))

        val ids = UsersCompat.getUsers(true, true, true) {
            throw AssertionError("Fallback must not be invoked when 3-boolean overload succeeds")
        }.map { it.id }

        assertEquals(listOf(0, 10), ids)
    }

    @Test
    fun `getUsers falls back to single-boolean excludeDying overload on Android 17 QPR1 NoSuchMethodError`() {
        userManagerMock.`when`<List<UserInfo>> {
            UserManagerApis.getUsers(anyBoolean(), anyBoolean(), anyBoolean())
        }.thenThrow(NoSuchMethodError("IUserManager.getUsers(ZZZ)"))

        var capturedExcludeDying: Boolean? = null
        val ids = UsersCompat.getUsers(
            excludePartial = true,
            excludeDying = false,
            excludePreCreated = true
        ) { excludeDying ->
            capturedExcludeDying = excludeDying
            makeUsers(0, 11)
        }.map { it.id }

        assertEquals(false, capturedExcludeDying)
        assertEquals(listOf(0, 11), ids)
    }

    @Test
    fun `getUserIdsNoThrow falls back to user 0 when UserManager fails`() {
        userManagerMock.`when`<List<UserInfo>> {
            UserManagerApis.getUsers(anyBoolean(), anyBoolean(), anyBoolean())
        }.thenThrow(IllegalStateException("user service dead"))

        assertEquals(listOf(0), UsersCompat.getUserIdsNoThrow())
    }
}
