/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.whispersystems.textsecuregcm.entities.SignedPreKey;
import org.whispersystems.textsecuregcm.tests.util.KeysHelper;

class SingleUseKEMPreKeyStoreTest extends SingleUsePreKeyStoreTest<SignedPreKey> {

  private SingleUseKEMPreKeyStore preKeyStore;

  private static final ECKeyPair IDENTITY_KEY_PAIR = Curve.generateKeyPair();

  @RegisterExtension
  static final DynamoDbExtension DYNAMO_DB_EXTENSION = new DynamoDbExtension(DynamoDbExtensionSchema.Tables.PQ_KEYS);

  @BeforeEach
  void setUp() {
    preKeyStore = new SingleUseKEMPreKeyStore(DYNAMO_DB_EXTENSION.getDynamoDbAsyncClient(),
        DynamoDbExtensionSchema.Tables.PQ_KEYS.tableName());
  }

  @Override
  protected SingleUsePreKeyStore<SignedPreKey> getPreKeyStore() {
    return preKeyStore;
  }

  @Override
  protected SignedPreKey generatePreKey(final long keyId) {
    return KeysHelper.signedKEMPreKey(keyId, IDENTITY_KEY_PAIR);
  }
}
