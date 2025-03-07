package org.eu.exodus_privacy.exodusprivacy

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.eu.exodus_privacy.exodusprivacy.manager.network.ExodusAPIInterface
import org.eu.exodus_privacy.exodusprivacy.manager.network.ExodusAPIRepository
import org.eu.exodus_privacy.exodusprivacy.manager.network.ExodusModule
import org.eu.exodus_privacy.exodusprivacy.manager.network.NetworkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

const val FAKE_PATH = "/api/requests/"
const val FAKE_PORT = 34567
const val FAKE_RESPONSE =
    """
        { "trackers": 
        {"1": {
      "id": 1,
      "name": "Teemo",
      "description": "Placeholder",
      "creation_date": "2017-09-24",
      "code_signature": "com.databerries.|com.geolocstation.",
      "network_signature": "databerries\\.com",
      "website": "https://www.teemo.co",
      "categories": [
        "Analytics"
      ],
      "documentation": []
    }
  }
}   
"""

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ExodusModule::class]
)
object FakeExodusModule {
    @Singleton
    @Provides
    fun provideExodusAPIInstance(okHttpClient: OkHttpClient): ExodusAPIInterface {
        return Retrofit.Builder()
            .baseUrl("http://localhost:$FAKE_PORT$FAKE_PATH")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(ExodusAPIInterface::class.java)
    }

    @Singleton
    @Provides
    fun provideInterceptor(): Interceptor {
        return Interceptor { chain ->
            val builder = chain.request().newBuilder()
            builder.header("Authorization", "Token ${BuildConfig.EXODUS_API_KEY}")
            return@Interceptor chain.proceed(builder.build())
        }
    }

    @Singleton
    @Provides
    fun provideOkHttpClient(interceptor: Interceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }
}

@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class ExodusAPIRepositoryTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val serviceRule = ServiceTestRule()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val testDispatcher = StandardTestDispatcher()

    private val mockWebServer = MockWebServer()
    private val socketPolicy = SocketPolicy.NO_RESPONSE

    @Before
    fun setup() {
        mockWebServer.start(FAKE_PORT)
        mockWebServer.url(FAKE_PATH)
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun exodusAPIRepoShouldTimeOut() = runTest(testDispatcher) {
        // given
        hiltRule.inject()

        @OptIn(ExperimentalCoroutinesApi::class)
        val exodusAPIRepository = ExodusAPIRepository(
            FakeExodusModule.provideExodusAPIInstance(
                FakeExodusModule.provideOkHttpClient(
                    FakeExodusModule.provideInterceptor()
                )
            ),
            NetworkManager(ApplicationProvider.getApplicationContext()),
            testDispatcher
        )

        // when
        val mockResponse = MockResponse()
            .setBody(FAKE_RESPONSE)
            .setResponseCode(200)
            .setSocketPolicy(socketPolicy)
        mockWebServer.enqueue(mockResponse)

        // then
        val exception =
            try {
                exodusAPIRepository.getAllTrackers()
            } catch (
                exception: java.net.SocketTimeoutException
            ) {
                exception
            }

        assertEquals(
            exception.toString(),
            "java.net.SocketTimeoutException: timeout"
        )
    }
}
