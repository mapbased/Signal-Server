/*
 * Copyright 2021-2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.whispersystems.textsecuregcm.entities.PreKey;
import org.whispersystems.textsecuregcm.entities.SignedPreKey;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtensionSchema.Tables;
import org.whispersystems.textsecuregcm.tests.util.KeysHelper;

class KeysManagerTest {

  private KeysManager keysManager;

  @RegisterExtension
  static final DynamoDbExtension DYNAMO_DB_EXTENSION = new DynamoDbExtension(
      Tables.EC_KEYS, Tables.PQ_KEYS, Tables.REPEATED_USE_SIGNED_PRE_KEYS);

  private static final UUID ACCOUNT_UUID = UUID.randomUUID();
  private static final long DEVICE_ID = 1L;

  @BeforeEach
  void setup() {
    keysManager = new KeysManager(
        DYNAMO_DB_EXTENSION.getDynamoDbAsyncClient(),
        Tables.EC_KEYS.tableName(),
        Tables.PQ_KEYS.tableName(),
        Tables.REPEATED_USE_SIGNED_PRE_KEYS.tableName());
  }

  @Test
  void testStore() {
    assertEquals(0, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID),
        "Initial pre-key count for an account should be zero");
    assertEquals(0, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID),
        "Initial pre-key count for an account should be zero");
    assertFalse(keysManager.getLastResort(ACCOUNT_UUID, DEVICE_ID).isPresent(),
        "Initial last-resort pre-key for an account should be missing");

    keysManager.store(ACCOUNT_UUID, DEVICE_ID, List.of(generateTestPreKey(1)));
    assertEquals(1, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID));

    keysManager.store(ACCOUNT_UUID, DEVICE_ID, List.of(generateTestPreKey(1)));
    assertEquals(1, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID),
        "Repeatedly storing same key should have no effect");

    keysManager.store(ACCOUNT_UUID, DEVICE_ID, null, List.of(generateTestSignedPreKey(1)), null);
    assertEquals(1, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID),
        "Uploading new PQ prekeys should have no effect on EC prekeys");
    assertEquals(1, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID));

    keysManager.store(ACCOUNT_UUID, DEVICE_ID, null, null, generateTestSignedPreKey(1001));
    assertEquals(1, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID),
        "Uploading new PQ last-resort prekey should have no effect on EC prekeys");
    assertEquals(1, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID),
        "Uploading new PQ last-resort prekey should have no effect on one-time PQ prekeys");
    assertEquals(1001, keysManager.getLastResort(ACCOUNT_UUID, DEVICE_ID).get().getKeyId());

    keysManager.store(ACCOUNT_UUID, DEVICE_ID, List.of(generateTestPreKey(2)), null, null);
    assertEquals(1, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID),
        "Inserting a new key should overwrite all prior keys of the same type for the given account/device");
    assertEquals(1, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID),
        "Uploading new EC prekeys should have no effect on PQ prekeys");

    keysManager.store(ACCOUNT_UUID, DEVICE_ID, List.of(generateTestPreKey(3)), List.of(generateTestSignedPreKey(2)), null);
    assertEquals(1, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID),
        "Inserting a new key should overwrite all prior keys of the same type for the given account/device");
    assertEquals(1, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID),
        "Inserting a new key should overwrite all prior keys of the same type for the given account/device");

    keysManager.store(ACCOUNT_UUID, DEVICE_ID,
        List.of(generateTestPreKey(4), generateTestPreKey(5)),
        List.of(generateTestSignedPreKey(6), generateTestSignedPreKey(7)),
        generateTestSignedPreKey(1002));
    assertEquals(2, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID),
        "Inserting multiple new keys should overwrite all prior keys for the given account/device");
    assertEquals(2, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID),
        "Inserting multiple new keys should overwrite all prior keys for the given account/device");
    assertEquals(1002, keysManager.getLastResort(ACCOUNT_UUID, DEVICE_ID).get().getKeyId(),
        "Uploading new last-resort key should overwrite prior last-resort key for the account/device");
  }

  @Test
  void testTakeAccountAndDeviceId() {
    assertEquals(Optional.empty(), keysManager.takeEC(ACCOUNT_UUID, DEVICE_ID));

    final PreKey preKey = generateTestPreKey(1);

    keysManager.store(ACCOUNT_UUID, DEVICE_ID, List.of(preKey, generateTestPreKey(2)));
    final Optional<PreKey> takenKey = keysManager.takeEC(ACCOUNT_UUID, DEVICE_ID);
    assertEquals(Optional.of(preKey), takenKey);
    assertEquals(1, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID));
  }

  @Test
  void testTakePQ() {
    assertEquals(Optional.empty(), keysManager.takeEC(ACCOUNT_UUID, DEVICE_ID));

    final SignedPreKey preKey1 = generateTestSignedPreKey(1);
    final SignedPreKey preKey2 = generateTestSignedPreKey(2);
    final SignedPreKey preKeyLast = generateTestSignedPreKey(1001);

    keysManager.store(ACCOUNT_UUID, DEVICE_ID, null, List.of(preKey1, preKey2), preKeyLast);

    assertEquals(Optional.of(preKey1), keysManager.takePQ(ACCOUNT_UUID, DEVICE_ID));
    assertEquals(1, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID));

    assertEquals(Optional.of(preKey2), keysManager.takePQ(ACCOUNT_UUID, DEVICE_ID));
    assertEquals(0, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID));

    assertEquals(Optional.of(preKeyLast), keysManager.takePQ(ACCOUNT_UUID, DEVICE_ID));
    assertEquals(0, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID));

    assertEquals(Optional.of(preKeyLast), keysManager.takePQ(ACCOUNT_UUID, DEVICE_ID));
    assertEquals(0, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID));
  }

  @Test
  void testGetCount() {
    assertEquals(0, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID));
    assertEquals(0, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID));

    keysManager.store(ACCOUNT_UUID, DEVICE_ID, List.of(generateTestPreKey(1)), List.of(generateTestSignedPreKey(1)), null);
    assertEquals(1, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID));
    assertEquals(1, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID));
  }

  @Test
  void testDeleteByAccount() {
    keysManager.store(ACCOUNT_UUID, DEVICE_ID,
        List.of(generateTestPreKey(1), generateTestPreKey(2)),
        List.of(generateTestSignedPreKey(3), generateTestSignedPreKey(4)),
        generateTestSignedPreKey(5));

    keysManager.store(ACCOUNT_UUID, DEVICE_ID + 1,
        List.of(generateTestPreKey(6)),
        List.of(generateTestSignedPreKey(7)),
        generateTestSignedPreKey(8));

    assertEquals(2, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID));
    assertEquals(2, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID));
    assertTrue(keysManager.getLastResort(ACCOUNT_UUID, DEVICE_ID).isPresent());
    assertEquals(1, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID + 1));
    assertEquals(1, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID + 1));
    assertTrue(keysManager.getLastResort(ACCOUNT_UUID, DEVICE_ID + 1).isPresent());

    keysManager.delete(ACCOUNT_UUID);

    assertEquals(0, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID));
    assertEquals(0, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID));
    assertFalse(keysManager.getLastResort(ACCOUNT_UUID, DEVICE_ID).isPresent());
    assertEquals(0, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID + 1));
    assertEquals(0, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID + 1));
    assertFalse(keysManager.getLastResort(ACCOUNT_UUID, DEVICE_ID + 1).isPresent());
  }

  @Test
  void testDeleteByAccountAndDevice() {
    keysManager.store(ACCOUNT_UUID, DEVICE_ID,
        List.of(generateTestPreKey(1), generateTestPreKey(2)),
        List.of(generateTestSignedPreKey(3), generateTestSignedPreKey(4)),
        generateTestSignedPreKey(5));

    keysManager.store(ACCOUNT_UUID, DEVICE_ID + 1,
        List.of(generateTestPreKey(6)),
        List.of(generateTestSignedPreKey(7)),
        generateTestSignedPreKey(8));

    assertEquals(2, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID));
    assertEquals(2, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID));
    assertTrue(keysManager.getLastResort(ACCOUNT_UUID, DEVICE_ID).isPresent());
    assertEquals(1, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID + 1));
    assertEquals(1, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID + 1));
    assertTrue(keysManager.getLastResort(ACCOUNT_UUID, DEVICE_ID + 1).isPresent());

    keysManager.delete(ACCOUNT_UUID, DEVICE_ID);

    assertEquals(0, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID));
    assertEquals(0, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID));
    assertFalse(keysManager.getLastResort(ACCOUNT_UUID, DEVICE_ID).isPresent());
    assertEquals(1, keysManager.getEcCount(ACCOUNT_UUID, DEVICE_ID + 1));
    assertEquals(1, keysManager.getPqCount(ACCOUNT_UUID, DEVICE_ID + 1));
    assertTrue(keysManager.getLastResort(ACCOUNT_UUID, DEVICE_ID + 1).isPresent());
  }

  @Test
  void testStorePqLastResort() {
    assertEquals(0, keysManager.getPqEnabledDevices(ACCOUNT_UUID).size());

    final ECKeyPair identityKeyPair = Curve.generateKeyPair();

    keysManager.storePqLastResort(
        ACCOUNT_UUID,
        Map.of(1L, KeysHelper.signedKEMPreKey(1, identityKeyPair), 2L, KeysHelper.signedKEMPreKey(2, identityKeyPair)));
    assertEquals(2, keysManager.getPqEnabledDevices(ACCOUNT_UUID).size());
    assertEquals(1L, keysManager.getLastResort(ACCOUNT_UUID, 1L).get().getKeyId());
    assertEquals(2L, keysManager.getLastResort(ACCOUNT_UUID, 2L).get().getKeyId());
    assertFalse(keysManager.getLastResort(ACCOUNT_UUID, 3L).isPresent());

    keysManager.storePqLastResort(
        ACCOUNT_UUID,
        Map.of(1L, KeysHelper.signedKEMPreKey(3, identityKeyPair), 3L, KeysHelper.signedKEMPreKey(4, identityKeyPair)));
    assertEquals(3, keysManager.getPqEnabledDevices(ACCOUNT_UUID).size(), "storing new last-resort keys should not create duplicates");
    assertEquals(3L, keysManager.getLastResort(ACCOUNT_UUID, 1L).get().getKeyId(), "storing new last-resort keys should overwrite old ones");
    assertEquals(2L, keysManager.getLastResort(ACCOUNT_UUID, 2L).get().getKeyId(), "storing new last-resort keys should leave untouched ones alone");
    assertEquals(4L, keysManager.getLastResort(ACCOUNT_UUID, 3L).get().getKeyId(), "storing new last-resort keys should overwrite old ones");
  }

  @Test
  void testGetPqEnabledDevices() {
    final ECKeyPair identityKeyPair = Curve.generateKeyPair();

    keysManager.store(ACCOUNT_UUID, DEVICE_ID, null, List.of(KeysHelper.signedKEMPreKey(1, identityKeyPair)), null);
    keysManager.store(ACCOUNT_UUID, DEVICE_ID + 1, null, null, KeysHelper.signedKEMPreKey(2, identityKeyPair));
    keysManager.store(ACCOUNT_UUID, DEVICE_ID + 2, null, List.of(KeysHelper.signedKEMPreKey(3, identityKeyPair)), KeysHelper.signedKEMPreKey(4, identityKeyPair));
    keysManager.store(ACCOUNT_UUID, DEVICE_ID + 3, null, null, null);
    assertIterableEquals(
        Set.of(DEVICE_ID + 1, DEVICE_ID + 2),
        Set.copyOf(keysManager.getPqEnabledDevices(ACCOUNT_UUID)));
  }

  private static PreKey generateTestPreKey(final long keyId) {
    final byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);

    return new PreKey(keyId, key);
  }

  private static SignedPreKey generateTestSignedPreKey(final long keyId) {
    final byte[] key = new byte[32];
    final byte[] signature = new byte[32];

    final SecureRandom secureRandom = new SecureRandom();
    secureRandom.nextBytes(key);
    secureRandom.nextBytes(signature);

    return new SignedPreKey(keyId, key, signature);
  }
}
