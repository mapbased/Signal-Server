/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.util.Base64;
import com.google.common.net.HttpHeaders;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.whispersystems.textsecuregcm.auth.AuthenticatedDevice;
import org.whispersystems.textsecuregcm.auth.WebsocketRefreshApplicationEventListener;
import org.whispersystems.textsecuregcm.entities.AccountAttributes;
import org.whispersystems.textsecuregcm.entities.ApnRegistrationId;
import org.whispersystems.textsecuregcm.entities.DeviceActivationRequest;
import org.whispersystems.textsecuregcm.entities.DeviceInfo;
import org.whispersystems.textsecuregcm.entities.DeviceResponse;
import org.whispersystems.textsecuregcm.entities.ECSignedPreKey;
import org.whispersystems.textsecuregcm.entities.GcmRegistrationId;
import org.whispersystems.textsecuregcm.entities.KEMSignedPreKey;
import org.whispersystems.textsecuregcm.entities.LinkDeviceRequest;
import org.whispersystems.textsecuregcm.entities.SetPublicKeyRequest;
import org.whispersystems.textsecuregcm.identity.IdentityType;
import org.whispersystems.textsecuregcm.limits.RateLimiter;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.mappers.DeviceLimitExceededExceptionMapper;
import org.whispersystems.textsecuregcm.mappers.RateLimitExceededExceptionMapper;
import org.whispersystems.textsecuregcm.push.ClientPresenceManager;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.ClientPublicKeysManager;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.storage.Device.DeviceCapabilities;
import org.whispersystems.textsecuregcm.storage.DeviceSpec;
import org.whispersystems.textsecuregcm.storage.LinkDeviceTokenAlreadyUsedException;
import org.whispersystems.textsecuregcm.tests.util.AccountsHelper;
import org.whispersystems.textsecuregcm.tests.util.AuthHelper;
import org.whispersystems.textsecuregcm.tests.util.KeysHelper;
import org.whispersystems.textsecuregcm.tests.util.MockRedisFuture;
import org.whispersystems.textsecuregcm.util.Pair;
import org.whispersystems.textsecuregcm.util.TestClock;
import org.whispersystems.textsecuregcm.util.TestRandomUtil;
import org.whispersystems.textsecuregcm.util.LinkDeviceToken;

@ExtendWith(DropwizardExtensionsSupport.class)
class DeviceControllerTest {

  private static AccountsManager accountsManager = mock(AccountsManager.class);
  private static ClientPublicKeysManager clientPublicKeysManager = mock(ClientPublicKeysManager.class);
  private static RateLimiters rateLimiters = mock(RateLimiters.class);
  private static RateLimiter rateLimiter = mock(RateLimiter.class);
  private static RedisAdvancedClusterCommands<String, String> commands = mock(RedisAdvancedClusterCommands.class);
  private static RedisAdvancedClusterAsyncCommands<String, String> asyncCommands = mock(RedisAdvancedClusterAsyncCommands.class);
  private static Account account = mock(Account.class);
  private static Account maxedAccount = mock(Account.class);
  private static Device primaryDevice = mock(Device.class);
  private static ClientPresenceManager clientPresenceManager = mock(ClientPresenceManager.class);
  private static Map<String, Integer> deviceConfiguration = new HashMap<>();
  private static TestClock testClock = TestClock.now();

  private static final byte NEXT_DEVICE_ID = 42;

  private static DeviceController deviceController = new DeviceController(
      accountsManager,
      clientPublicKeysManager,
      rateLimiters,
      deviceConfiguration);

  @RegisterExtension
  public static final AuthHelper.AuthFilterExtension AUTH_FILTER_EXTENSION = new AuthHelper.AuthFilterExtension();

  private static final ResourceExtension resources = ResourceExtension.builder()
      .addProperty(ServerProperties.UNWRAP_COMPLETION_STAGE_IN_WRITER_ENABLE, Boolean.TRUE)
      .addProvider(AuthHelper.getAuthFilter())
      .addProvider(new AuthValueFactoryProvider.Binder<>(AuthenticatedDevice.class))
      .addProvider(new RateLimitExceededExceptionMapper())
      .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
      .addProvider(new WebsocketRefreshApplicationEventListener(accountsManager, clientPresenceManager))
      .addProvider(new DeviceLimitExceededExceptionMapper())
      .addResource(deviceController)
      .build();

  @BeforeEach
  void setup() {
    when(rateLimiters.getAllocateDeviceLimiter()).thenReturn(rateLimiter);
    when(rateLimiters.getVerifyDeviceLimiter()).thenReturn(rateLimiter);
    when(rateLimiters.getWaitForLinkedDeviceLimiter()).thenReturn(rateLimiter);

    when(primaryDevice.getId()).thenReturn(Device.PRIMARY_ID);

    when(account.getNextDeviceId()).thenReturn(NEXT_DEVICE_ID);
    when(account.getNumber()).thenReturn(AuthHelper.VALID_NUMBER);
    when(account.getUuid()).thenReturn(AuthHelper.VALID_UUID);
    when(account.getPhoneNumberIdentifier()).thenReturn(AuthHelper.VALID_PNI);

    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(account));
    when(accountsManager.getByE164(AuthHelper.VALID_NUMBER)).thenReturn(Optional.of(account));
    when(accountsManager.getByE164(AuthHelper.VALID_NUMBER_TWO)).thenReturn(Optional.of(maxedAccount));

    when(clientPublicKeysManager.setPublicKey(any(), anyByte(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));

    AccountsHelper.setupMockUpdate(accountsManager);
  }

  @AfterEach
  void teardown() {
    reset(
        accountsManager,
        rateLimiters,
        rateLimiter,
        commands,
        asyncCommands,
        account,
        maxedAccount,
        primaryDevice,
        clientPresenceManager
    );

    testClock.unpin();
  }

  @ParameterizedTest
  @MethodSource
  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  void linkDeviceAtomic(final boolean fetchesMessages,
                        final Optional<ApnRegistrationId> apnRegistrationId,
                        final Optional<GcmRegistrationId> gcmRegistrationId,
                        final Optional<String> expectedApnsToken,
                        final Optional<String> expectedGcmToken) {

    final Device existingDevice = mock(Device.class);
    when(existingDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(existingDevice));

    final ECSignedPreKey aciSignedPreKey;
    final ECSignedPreKey pniSignedPreKey;
    final KEMSignedPreKey aciPqLastResortPreKey;
    final KEMSignedPreKey pniPqLastResortPreKey;

    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(new IdentityKey(aciIdentityKeyPair.getPublicKey()));
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(new IdentityKey(pniIdentityKeyPair.getPublicKey()));

    when(accountsManager.checkDeviceLinkingToken(anyString())).thenReturn(Optional.of(AuthHelper.VALID_UUID));

    when(accountsManager.addDevice(any(), any(), any())).thenAnswer(invocation -> {
      final Account a = invocation.getArgument(0);
      final DeviceSpec deviceSpec = invocation.getArgument(1);

      return CompletableFuture.completedFuture(new Pair<>(a, deviceSpec.toDevice(NEXT_DEVICE_ID, testClock)));
    });

    when(asyncCommands.set(any(), any(), any())).thenReturn(MockRedisFuture.completedFuture(null));

    final AccountAttributes accountAttributes = new AccountAttributes(fetchesMessages, 1234, 5678, null,
        null, true, new DeviceCapabilities(true, true, false, false));

    final LinkDeviceRequest request = new LinkDeviceRequest("link-device-token",
        accountAttributes,
        new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, apnRegistrationId, gcmRegistrationId));

    final DeviceResponse response = resources.getJerseyTest()
        .target("/v1/devices/link")
        .request()
        .header("Authorization", AuthHelper.getProvisioningAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE), DeviceResponse.class);

    assertThat(response.getDeviceId()).isEqualTo(NEXT_DEVICE_ID);

    final ArgumentCaptor<DeviceSpec> deviceSpecCaptor = ArgumentCaptor.forClass(DeviceSpec.class);
    verify(accountsManager).addDevice(eq(account), deviceSpecCaptor.capture(), any());

    final Device device = deviceSpecCaptor.getValue().toDevice(NEXT_DEVICE_ID, testClock);

    assertEquals(fetchesMessages, device.getFetchesMessages());

    expectedApnsToken.ifPresentOrElse(expectedToken -> assertEquals(expectedToken, device.getApnId()),
        () -> assertNull(device.getApnId()));

    expectedGcmToken.ifPresentOrElse(expectedToken -> assertEquals(expectedToken, device.getGcmId()),
        () -> assertNull(device.getGcmId()));
  }

  private static Stream<Arguments> linkDeviceAtomic() {
    final String apnsToken = "apns-token";
    final String gcmToken = "gcm-token";

    return Stream.of(
        Arguments.of(true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
        Arguments.of(false, Optional.of(new ApnRegistrationId(apnsToken)), Optional.empty(), Optional.of(apnsToken), Optional.empty()),
        Arguments.of(false, Optional.of(new ApnRegistrationId(apnsToken)), Optional.empty(), Optional.of(apnsToken), Optional.empty()),
        Arguments.of(false, Optional.empty(), Optional.of(new GcmRegistrationId(gcmToken)), Optional.empty(), Optional.of(gcmToken))
    );
  }

  @ParameterizedTest
  @MethodSource
  void deviceDowngradeDeleteSync(final boolean accountSupportsDeleteSync, final boolean deviceSupportsDeleteSync, final int expectedStatus) {
    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(account));
    when(accountsManager.addDevice(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(new Pair<>(mock(Account.class), mock(Device.class))));

    final Device primaryDevice = mock(Device.class);
    when(primaryDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(primaryDevice));

    final ECSignedPreKey aciSignedPreKey;
    final ECSignedPreKey pniSignedPreKey;
    final KEMSignedPreKey aciPqLastResortPreKey;
    final KEMSignedPreKey pniPqLastResortPreKey;

    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(new IdentityKey(aciIdentityKeyPair.getPublicKey()));
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(new IdentityKey(pniIdentityKeyPair.getPublicKey()));
    when(account.isDeleteSyncSupported()).thenReturn(accountSupportsDeleteSync);

    when(asyncCommands.set(any(), any(), any())).thenReturn(MockRedisFuture.completedFuture(null));

    when(accountsManager.checkDeviceLinkingToken(anyString())).thenReturn(Optional.of(AuthHelper.VALID_UUID));

    final LinkDeviceRequest request = new LinkDeviceRequest("link-device-token",
            new AccountAttributes(false, 1234, 5678, null, null, true, new DeviceCapabilities(true, true, deviceSupportsDeleteSync, false)),
            new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, Optional.empty(), Optional.of(new GcmRegistrationId("gcm-id"))));

    try (final Response response = resources.getJerseyTest()
            .target("/v1/devices/link")
            .request()
            .header("Authorization", AuthHelper.getProvisioningAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
            .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

      assertEquals(expectedStatus, response.getStatus());
    }
  }

  private static List<Arguments> deviceDowngradeDeleteSync() {
    return List.of(
            Arguments.of(true, true, 200),
            Arguments.of(true, false, 409),
            Arguments.of(false, true, 200),
            Arguments.of(false, false, 200));
  }

  @ParameterizedTest
  @MethodSource
  void deviceDowngradeVersionedExpirationTimer(final boolean accountSupportsVersionedExpirationTimer,
      final boolean deviceSupportsVersionedExpirationTimer, final int expectedStatus) {
    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(account));
    when(accountsManager.addDevice(any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(new Pair<>(mock(Account.class), mock(Device.class))));

    final Device primaryDevice = mock(Device.class);
    when(primaryDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(primaryDevice));

    final ECSignedPreKey aciSignedPreKey;
    final ECSignedPreKey pniSignedPreKey;
    final KEMSignedPreKey aciPqLastResortPreKey;
    final KEMSignedPreKey pniPqLastResortPreKey;

    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(new IdentityKey(aciIdentityKeyPair.getPublicKey()));
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(new IdentityKey(pniIdentityKeyPair.getPublicKey()));
    when(account.isDeleteSyncSupported()).thenReturn(accountSupportsVersionedExpirationTimer);

    when(asyncCommands.set(any(), any(), any())).thenReturn(MockRedisFuture.completedFuture(null));

    when(accountsManager.checkDeviceLinkingToken(anyString())).thenReturn(Optional.of(AuthHelper.VALID_UUID));

    final LinkDeviceRequest request = new LinkDeviceRequest("link-device-token",
        new AccountAttributes(false, 1234, 5678, null, null, true, new DeviceCapabilities(true, true, deviceSupportsVersionedExpirationTimer, false)),
        new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, Optional.empty(), Optional.of(new GcmRegistrationId("gcm-id"))));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/link")
        .request()
        .header("Authorization", AuthHelper.getProvisioningAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

      assertEquals(expectedStatus, response.getStatus());
    }
  }

  private static List<Arguments> deviceDowngradeVersionedExpirationTimer() {
    return List.of(
        Arguments.of(true, true, 200),
        Arguments.of(true, false, 409),
        Arguments.of(false, true, 200),
        Arguments.of(false, false, 200));
  }

  @Test
  void linkDeviceAtomicBadCredentials() {
    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(account));

    final Device primaryDevice = mock(Device.class);
    when(primaryDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(primaryDevice));

    final ECSignedPreKey aciSignedPreKey;
    final ECSignedPreKey pniSignedPreKey;
    final KEMSignedPreKey aciPqLastResortPreKey;
    final KEMSignedPreKey pniPqLastResortPreKey;

    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(new IdentityKey(aciIdentityKeyPair.getPublicKey()));
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(new IdentityKey(pniIdentityKeyPair.getPublicKey()));

    final LinkDeviceRequest request = new LinkDeviceRequest("link-device-token",
        new AccountAttributes(false, 1234, 5678, null, null, true, null),
        new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, Optional.empty(), Optional.of(new GcmRegistrationId("gcm-id"))));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/link")
        .request()
        .header("Authorization", "This is not a valid authorization header")
        .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

      assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
    }
  }

  @Test
  void linkDeviceAtomicReusedToken() {
    final Device existingDevice = mock(Device.class);
    when(existingDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(existingDevice));

    final ECSignedPreKey aciSignedPreKey;
    final ECSignedPreKey pniSignedPreKey;
    final KEMSignedPreKey aciPqLastResortPreKey;
    final KEMSignedPreKey pniPqLastResortPreKey;

    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(new IdentityKey(aciIdentityKeyPair.getPublicKey()));
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(new IdentityKey(pniIdentityKeyPair.getPublicKey()));

    when(accountsManager.checkDeviceLinkingToken(anyString())).thenReturn(Optional.of(AuthHelper.VALID_UUID));

    when(accountsManager.addDevice(any(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new LinkDeviceTokenAlreadyUsedException()));

    when(asyncCommands.set(any(), any(), any())).thenReturn(MockRedisFuture.completedFuture(null));

    final AccountAttributes accountAttributes = new AccountAttributes(true, 1234, 5678, null,
        null, true, new DeviceCapabilities(true, true, false, false));

    final LinkDeviceRequest request = new LinkDeviceRequest("link-device-token",
        accountAttributes,
        new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, Optional.empty(), Optional.empty()));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/link")
        .request()
        .header("Authorization", AuthHelper.getProvisioningAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

      assertEquals(403, response.getStatus());
    }
  }

  @Test
  void linkDeviceAtomicWithVerificationTokenUsed() {

    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(account));

    final Device existingDevice = mock(Device.class);
    when(existingDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(existingDevice));

    final ECSignedPreKey aciSignedPreKey;
    final ECSignedPreKey pniSignedPreKey;
    final KEMSignedPreKey aciPqLastResortPreKey;
    final KEMSignedPreKey pniPqLastResortPreKey;

    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(new IdentityKey(aciIdentityKeyPair.getPublicKey()));
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(new IdentityKey(pniIdentityKeyPair.getPublicKey()));

    when(commands.get(anyString())).thenReturn("");

    final LinkDeviceRequest request = new LinkDeviceRequest("link-device-token",
            new AccountAttributes(false, 1234, 5678, null, null, true, null),
            new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, Optional.empty(), Optional.of(new GcmRegistrationId("gcm-id"))));

    try (final Response response = resources.getJerseyTest()
            .target("/v1/devices/link")
            .request()
            .header("Authorization", AuthHelper.getProvisioningAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
            .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

      assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
    }
  }

  @ParameterizedTest
  @MethodSource
  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  void linkDeviceAtomicConflictingChannel(final boolean fetchesMessages,
                                          final Optional<ApnRegistrationId> apnRegistrationId,
                                          final Optional<GcmRegistrationId> gcmRegistrationId) {
    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(AuthHelper.VALID_ACCOUNT));
    when(accountsManager.generateLinkDeviceToken(any())).thenReturn("test");

    final Device existingDevice = mock(Device.class);
    when(existingDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(existingDevice));

    final LinkDeviceToken deviceCode = resources.getJerseyTest()
        .target("/v1/devices/provisioning/code")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(LinkDeviceToken.class);

    final ECSignedPreKey aciSignedPreKey;
    final ECSignedPreKey pniSignedPreKey;
    final KEMSignedPreKey aciPqLastResortPreKey;
    final KEMSignedPreKey pniPqLastResortPreKey;

    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(new IdentityKey(aciIdentityKeyPair.getPublicKey()));
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(new IdentityKey(pniIdentityKeyPair.getPublicKey()));

    final LinkDeviceRequest request = new LinkDeviceRequest(deviceCode.token(),
        new AccountAttributes(fetchesMessages, 1234, 5678, null, null, true, null),
        new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, apnRegistrationId, gcmRegistrationId));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/link")
        .request()
        .header("Authorization", AuthHelper.getProvisioningAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

      assertEquals(422, response.getStatus());
    }
  }

  private static Stream<Arguments> linkDeviceAtomicConflictingChannel() {
    return Stream.of(
        Arguments.of(true, Optional.of(new ApnRegistrationId("apns-token")), Optional.of(new GcmRegistrationId("gcm-token"))),
        Arguments.of(true, Optional.empty(), Optional.of(new GcmRegistrationId("gcm-token"))),
        Arguments.of(true, Optional.of(new ApnRegistrationId("apns-token")), Optional.empty()),
        Arguments.of(false, Optional.of(new ApnRegistrationId("apns-token")), Optional.of(new GcmRegistrationId("gcm-token")))
    );
  }

  @ParameterizedTest
  @MethodSource
  void linkDeviceAtomicMissingProperty(final IdentityKey aciIdentityKey,
                                       final IdentityKey pniIdentityKey,
                                       final ECSignedPreKey aciSignedPreKey,
                                       final ECSignedPreKey pniSignedPreKey,
                                       final KEMSignedPreKey aciPqLastResortPreKey,
                                       final KEMSignedPreKey pniPqLastResortPreKey) {

    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(AuthHelper.VALID_ACCOUNT));
    when(accountsManager.generateLinkDeviceToken(any())).thenReturn("test");

    final Device existingDevice = mock(Device.class);
    when(existingDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(existingDevice));

    final LinkDeviceToken deviceCode = resources.getJerseyTest()
        .target("/v1/devices/provisioning/code")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(LinkDeviceToken.class);

    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(aciIdentityKey);
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(pniIdentityKey);

    final LinkDeviceRequest request = new LinkDeviceRequest(deviceCode.token(),
        new AccountAttributes(true, 1234, 5678, null, null, true, null),
        new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, Optional.empty(), Optional.empty()));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/link")
        .request()
        .header("Authorization", AuthHelper.getProvisioningAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

      assertEquals(422, response.getStatus());
    }
  }

  private static Stream<Arguments> linkDeviceAtomicMissingProperty() {
    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    final ECSignedPreKey aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    final ECSignedPreKey pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    final KEMSignedPreKey aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    final KEMSignedPreKey pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    final IdentityKey aciIdentityKey = new IdentityKey(aciIdentityKeyPair.getPublicKey());
    final IdentityKey pniIdentityKey = new IdentityKey(pniIdentityKeyPair.getPublicKey());

    return Stream.of(
        Arguments.of(aciIdentityKey, pniIdentityKey, null, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey),
        Arguments.of(aciIdentityKey, pniIdentityKey, aciSignedPreKey, null, aciPqLastResortPreKey, pniPqLastResortPreKey),
        Arguments.of(aciIdentityKey, pniIdentityKey, aciSignedPreKey, pniSignedPreKey, null, pniPqLastResortPreKey),
        Arguments.of(aciIdentityKey, pniIdentityKey, aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, null)
    );
  }

  @Test
  void linkDeviceAtomicMissingCapabilities() {
    final ECSignedPreKey aciSignedPreKey;
    final ECSignedPreKey pniSignedPreKey;
    final KEMSignedPreKey aciPqLastResortPreKey;
    final KEMSignedPreKey pniPqLastResortPreKey;

    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(AuthHelper.VALID_ACCOUNT));

    final Device existingDevice = mock(Device.class);
    when(existingDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(existingDevice));

    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(new IdentityKey(aciIdentityKeyPair.getPublicKey()));
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(new IdentityKey(pniIdentityKeyPair.getPublicKey()));

    when(accountsManager.checkDeviceLinkingToken(anyString())).thenReturn(Optional.of(AuthHelper.VALID_UUID));

    final LinkDeviceRequest request = new LinkDeviceRequest("link-device-token",
        new AccountAttributes(true, 1234, 5678, null, null, true, null),
        new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, Optional.empty(), Optional.empty()));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/link")
        .request()
        .header("Authorization", AuthHelper.getProvisioningAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

      assertEquals(422, response.getStatus());
    }
  }

  @ParameterizedTest
  @MethodSource
  void linkDeviceAtomicInvalidSignature(final IdentityKey aciIdentityKey,
                                        final IdentityKey pniIdentityKey,
                                        final ECSignedPreKey aciSignedPreKey,
                                        final ECSignedPreKey pniSignedPreKey,
                                        final KEMSignedPreKey aciPqLastResortPreKey,
                                        final KEMSignedPreKey pniPqLastResortPreKey) {

    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(AuthHelper.VALID_ACCOUNT));

    final Device existingDevice = mock(Device.class);
    when(existingDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(existingDevice));
    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(aciIdentityKey);
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(pniIdentityKey);

    when(accountsManager.checkDeviceLinkingToken(anyString())).thenReturn(Optional.of(AuthHelper.VALID_UUID));

    final LinkDeviceRequest request = new LinkDeviceRequest("link-device-token",
        new AccountAttributes(true, 1234, 5678, null, null, true, null),
        new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, Optional.empty(), Optional.empty()));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/link")
        .request()
        .header("Authorization", AuthHelper.getProvisioningAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

      assertEquals(422, response.getStatus());
    }
  }

  private static Stream<Arguments> linkDeviceAtomicInvalidSignature() {
    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    final ECSignedPreKey aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    final ECSignedPreKey pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    final KEMSignedPreKey aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    final KEMSignedPreKey pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    final IdentityKey aciIdentityKey = new IdentityKey(aciIdentityKeyPair.getPublicKey());
    final IdentityKey pniIdentityKey = new IdentityKey(pniIdentityKeyPair.getPublicKey());

    return Stream.of(
        Arguments.of(aciIdentityKey, pniIdentityKey, ecSignedPreKeyWithBadSignature(aciSignedPreKey), pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey),
        Arguments.of(aciIdentityKey, pniIdentityKey, aciSignedPreKey, ecSignedPreKeyWithBadSignature(pniSignedPreKey), aciPqLastResortPreKey, pniPqLastResortPreKey),
        Arguments.of(aciIdentityKey, pniIdentityKey, aciSignedPreKey, pniSignedPreKey, kemSignedPreKeyWithBadSignature(aciPqLastResortPreKey), pniPqLastResortPreKey),
        Arguments.of(aciIdentityKey, pniIdentityKey, aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, kemSignedPreKeyWithBadSignature(pniPqLastResortPreKey))
    );
  }

  @Test
  void linkDeviceAtomicExcessiveDeviceName() {

    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(account));

    final Device existingDevice = mock(Device.class);
    when(existingDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(existingDevice));

    final ECSignedPreKey aciSignedPreKey;
    final ECSignedPreKey pniSignedPreKey;
    final KEMSignedPreKey aciPqLastResortPreKey;
    final KEMSignedPreKey pniPqLastResortPreKey;

    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(new IdentityKey(aciIdentityKeyPair.getPublicKey()));
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(new IdentityKey(pniIdentityKeyPair.getPublicKey()));

    final LinkDeviceRequest request = new LinkDeviceRequest("link-device-token",
        new AccountAttributes(false, 1234, 5678, TestRandomUtil.nextBytes(512), null, true, null),
        new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, Optional.empty(), Optional.of(new GcmRegistrationId("gcm-id"))));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/link")
        .request()
        .header("Authorization", AuthHelper.getProvisioningAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

      assertEquals(422, response.getStatus());
    }
  }

  @ParameterizedTest
  @MethodSource
  void linkDeviceRegistrationId(final int registrationId, final int pniRegistrationId, final int expectedStatusCode) {
    final Device existingDevice = mock(Device.class);
    when(existingDevice.getId()).thenReturn(Device.PRIMARY_ID);
    when(AuthHelper.VALID_ACCOUNT.getDevices()).thenReturn(List.of(existingDevice));

    final ECKeyPair aciIdentityKeyPair = Curve.generateKeyPair();
    final ECKeyPair pniIdentityKeyPair = Curve.generateKeyPair();

    final ECSignedPreKey aciSignedPreKey = KeysHelper.signedECPreKey(1, aciIdentityKeyPair);
    final ECSignedPreKey pniSignedPreKey = KeysHelper.signedECPreKey(2, pniIdentityKeyPair);
    final KEMSignedPreKey aciPqLastResortPreKey = KeysHelper.signedKEMPreKey(3, aciIdentityKeyPair);
    final KEMSignedPreKey pniPqLastResortPreKey = KeysHelper.signedKEMPreKey(4, pniIdentityKeyPair);

    when(account.getIdentityKey(IdentityType.ACI)).thenReturn(new IdentityKey(aciIdentityKeyPair.getPublicKey()));
    when(account.getIdentityKey(IdentityType.PNI)).thenReturn(new IdentityKey(pniIdentityKeyPair.getPublicKey()));

    when(accountsManager.addDevice(any(), any(), any())).thenAnswer(invocation -> {
      final Account a = invocation.getArgument(0);
      final DeviceSpec deviceSpec = invocation.getArgument(1);

      return CompletableFuture.completedFuture(new Pair<>(a, deviceSpec.toDevice(NEXT_DEVICE_ID, testClock)));
    });

    when(accountsManager.checkDeviceLinkingToken(anyString())).thenReturn(Optional.of(AuthHelper.VALID_UUID));

    when(asyncCommands.set(any(), any(), any())).thenReturn(MockRedisFuture.completedFuture(null));

    final LinkDeviceRequest request = new LinkDeviceRequest("link-device-token",
        new AccountAttributes(false, registrationId, pniRegistrationId, null, null, true, new DeviceCapabilities(true, true, false, false)),
        new DeviceActivationRequest(aciSignedPreKey, pniSignedPreKey, aciPqLastResortPreKey, pniPqLastResortPreKey, Optional.of(new ApnRegistrationId("apn")), Optional.empty()));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/link")
        .request()
        .header("Authorization", AuthHelper.getProvisioningAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {
      assertEquals(expectedStatusCode, response.getStatus());
    }
  }

  private static Stream<Arguments> linkDeviceRegistrationId() {
    return Stream.of(
        Arguments.of(0x3FFF, 0x3FFF, 200),
        Arguments.of(0, 0x3FFF, 422),
        Arguments.of(-1, 0x3FFF, 422),
        Arguments.of(0x3FFF + 1, 0x3FFF, 422),
        Arguments.of(Integer.MAX_VALUE, 0x3FFF, 422),
        Arguments.of(0x3FFF, 0, 422),
        Arguments.of(0x3FFF, -1, 422),
        Arguments.of(0x3FFF, 0x3FFF + 1, 422),
        Arguments.of(0x3FFF, Integer.MAX_VALUE, 422)
    );
  }

  private static ECSignedPreKey ecSignedPreKeyWithBadSignature(final ECSignedPreKey signedPreKey) {
    return new ECSignedPreKey(signedPreKey.keyId(),
        signedPreKey.publicKey(),
        "incorrect-signature".getBytes(StandardCharsets.UTF_8));
  }

  private static KEMSignedPreKey kemSignedPreKeyWithBadSignature(final KEMSignedPreKey signedPreKey) {
    return new KEMSignedPreKey(signedPreKey.keyId(),
        signedPreKey.publicKey(),
        "incorrect-signature".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void maxDevicesTest() {
    final AuthHelper.TestAccount testAccount = AUTH_FILTER_EXTENSION.createTestAccount();

    final List<Device> devices = IntStream.range(0, DeviceController.MAX_DEVICES + 1)
        .mapToObj(i -> mock(Device.class))
        .toList();
    when(testAccount.account.getDevices()).thenReturn(devices);

    Response response = resources.getJerseyTest()
        .target("/v1/devices/provisioning/code")
        .request()
        .header("Authorization", testAccount.getAuthHeader())
        .get();

    assertEquals(411, response.getStatus());
    verify(accountsManager, never()).addDevice(any(), any(), any());
  }

  @Test
  void putCapabilitiesSuccessTest() {
    final DeviceCapabilities deviceCapabilities = new DeviceCapabilities(true, true, false, false);
    final Response response = resources
        .getJerseyTest()
        .target("/v1/devices/capabilities")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .header(HttpHeaders.USER_AGENT, "Signal-Android/5.42.8675309 Android/30")
        .put(Entity.entity(deviceCapabilities, MediaType.APPLICATION_JSON_TYPE));
    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(response.hasEntity()).isFalse();
  }

  @Test
  void putCapabilitiesFailureTest() {
    final Response response = resources
        .getJerseyTest()
        .target("/v1/devices/capabilities")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .header(HttpHeaders.USER_AGENT, "Signal-Android/5.42.8675309 Android/30")
        .put(Entity.json(""));
    assertThat(response.getStatus()).isEqualTo(422);
  }

  @Test
  void removeDevice() {

    // this is a static mock, so it might have previous invocations
    clearInvocations(AuthHelper.VALID_ACCOUNT);

    final byte deviceId = 2;

    when(accountsManager.removeDevice(AuthHelper.VALID_ACCOUNT, deviceId))
        .thenReturn(CompletableFuture.completedFuture(AuthHelper.VALID_ACCOUNT));

    final Response response = resources
        .getJerseyTest()
        .target("/v1/devices/" + deviceId)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .header(HttpHeaders.USER_AGENT, "Signal-Android/5.42.8675309 Android/30")
        .delete();

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(response.hasEntity()).isFalse();

    verify(accountsManager).removeDevice(AuthHelper.VALID_ACCOUNT, deviceId);
  }

  @Test
  void unlinkPrimaryDevice() {
    // this is a static mock, so it might have previous invocations
    clearInvocations(AuthHelper.VALID_ACCOUNT);

    try (final Response response = resources
        .getJerseyTest()
        .target("/v1/devices/" + Device.PRIMARY_ID)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .header(HttpHeaders.USER_AGENT, "Signal-Android/5.42.8675309 Android/30")
        .delete()) {

      assertThat(response.getStatus()).isEqualTo(403);

      verify(accountsManager, never()).removeDevice(any(), anyByte());
    }
  }

  @Test
  void removeDeviceBySelf() {
    final byte deviceId = 2;

    when(accountsManager.removeDevice(AuthHelper.VALID_ACCOUNT_3, deviceId))
        .thenReturn(CompletableFuture.completedFuture(AuthHelper.VALID_ACCOUNT));

    final Response response = resources
        .getJerseyTest()
        .target("/v1/devices/" + deviceId)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_3, deviceId, AuthHelper.VALID_PASSWORD_3_LINKED))
        .header(HttpHeaders.USER_AGENT, "Signal-Android/5.42.8675309 Android/30")
        .delete();

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(response.hasEntity()).isFalse();

    verify(accountsManager).removeDevice(AuthHelper.VALID_ACCOUNT_3, deviceId);
  }

  @Test
  void removeDeviceByOther() {
    final byte deviceId = 2;
    final byte otherDeviceId = 3;

    try (final Response response = resources
        .getJerseyTest()
        .target("/v1/devices/" + otherDeviceId)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_3, deviceId, AuthHelper.VALID_PASSWORD_3_LINKED))
        .header(HttpHeaders.USER_AGENT, "Signal-Android/5.42.8675309 Android/30")
        .delete()) {

      assertThat(response.getStatus()).isEqualTo(401);

      verify(accountsManager, never()).removeDevice(any(), anyByte());
    }
  }

  @Test
  void setPublicKey() {
    final SetPublicKeyRequest request = new SetPublicKeyRequest(Curve.generateKeyPair().getPublicKey());

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/public_key")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .put(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE))) {

      assertEquals(204, response.getStatus());
    }

    verify(clientPublicKeysManager).setPublicKey(AuthHelper.VALID_ACCOUNT, AuthHelper.VALID_DEVICE.getId(), request.publicKey());
  }

  @Test
  void waitForLinkedDevice() {
    final DeviceInfo deviceInfo = new DeviceInfo(Device.PRIMARY_ID,
        "Device name ciphertext".getBytes(StandardCharsets.UTF_8),
        System.currentTimeMillis(),
        System.currentTimeMillis());

    final String tokenIdentifier = Base64.encodeAsString(new byte[32]);

    when(accountsManager.waitForNewLinkedDevice(eq(tokenIdentifier), any()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(deviceInfo)));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/wait_for_linked_device/" + tokenIdentifier)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get()) {

      assertEquals(200, response.getStatus());

      final DeviceInfo retrievedDeviceInfo = response.readEntity(DeviceInfo.class);
      assertEquals(deviceInfo.id(), retrievedDeviceInfo.id());
      assertArrayEquals(deviceInfo.name(), retrievedDeviceInfo.name());
      assertEquals(deviceInfo.created(), retrievedDeviceInfo.created());
      assertEquals(deviceInfo.lastSeen(), retrievedDeviceInfo.lastSeen());
    }
  }

  @Test
  void waitForLinkedDeviceNoDeviceLinked() {
    final String tokenIdentifier = Base64.encodeAsString(new byte[32]);

    when(accountsManager.waitForNewLinkedDevice(eq(tokenIdentifier), any()))
        .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/wait_for_linked_device/" + tokenIdentifier)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get()) {

      assertEquals(204, response.getStatus());
    }
  }

  @Test
  void waitForLinkedDeviceBadTokenIdentifier() {
    final String tokenIdentifier = Base64.encodeAsString(new byte[32]);

    when(accountsManager.waitForNewLinkedDevice(eq(tokenIdentifier), any()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException()));

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/wait_for_linked_device/" + tokenIdentifier)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get()) {

      assertEquals(400, response.getStatus());
    }
  }

  @ParameterizedTest
  @MethodSource
  void waitForLinkedDeviceBadTimeout(final int timeoutSeconds) {
    final String tokenIdentifier = Base64.encodeAsString(new byte[32]);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/wait_for_linked_device/" + tokenIdentifier)
        .queryParam("timeout", timeoutSeconds)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get()) {

      assertEquals(400, response.getStatus());
    }
  }

  private static List<Integer> waitForLinkedDeviceBadTimeout() {
    return List.of(0, -1, 3601);
  }

  @ParameterizedTest
  @MethodSource
  void waitForLinkedDeviceBadTokenIdentifierLength(final String tokenIdentifier) {
    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/wait_for_linked_device/" + tokenIdentifier)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get()) {

      assertEquals(400, response.getStatus());
    }
  }

  private static List<String> waitForLinkedDeviceBadTokenIdentifierLength() {
    return List.of(RandomStringUtils.randomAlphanumeric(DeviceController.MIN_TOKEN_IDENTIFIER_LENGTH - 1),
        RandomStringUtils.randomAlphanumeric(DeviceController.MAX_TOKEN_IDENTIFIER_LENGTH + 1));
  }

  @Test
  void waitForLinkedDeviceRateLimited() throws RateLimitExceededException {
    final String tokenIdentifier = Base64.encodeAsString(new byte[32]);

    doThrow(new RateLimitExceededException(null)).when(rateLimiter).validate(AuthHelper.VALID_UUID);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/devices/wait_for_linked_device/" + tokenIdentifier)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get()) {

      assertEquals(429, response.getStatus());
    }
  }
}
