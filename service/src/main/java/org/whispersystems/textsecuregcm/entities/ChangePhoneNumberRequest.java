/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import org.whispersystems.textsecuregcm.util.ByteArrayAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.validation.constraints.AssertTrue;
import javax.annotation.Nullable;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import org.whispersystems.textsecuregcm.util.ValidPreKey;
import org.whispersystems.textsecuregcm.util.ValidPreKey.PreKeyType;

public record ChangePhoneNumberRequest(
    @Schema(description="the new phone number for this account")
    @NotBlank String number,

    @Schema(description="the registration verification code to authenticate this request")
    @NotBlank String code,

    @Schema(description="the registration lock password for the new phone number, if necessary")
    @JsonProperty("reglock") @Nullable String registrationLock,

    @Schema(description="the new public identity key to use for the phone-number identity associated with the new phone number")
    @JsonDeserialize(using = ByteArrayAdapter.Deserializing.class)
    @Nullable byte[] pniIdentityKey,

    @Schema(description="""
        A list of synchronization messages to send to companion devices to supply the private keysManager
        associated with the new identity key and their new prekeys.
        Exactly one message must be supplied for each enabled device other than the sending (primary) device.""")
    @Nullable List<IncomingMessage> deviceMessages,

    @Schema(description="""
        A new signed elliptic-curve prekey for each enabled device on the account, including this one.
        Each must be accompanied by a valid signature from the new identity key in this request.""")
    @Nullable Map<Long, @ValidPreKey(type=PreKeyType.ECC) SignedPreKey> devicePniSignedPrekeys,

    @Schema(description="""
        A new signed post-quantum last-resort prekey for each enabled device on the account, including this one.
        May be absent, in which case the last resort PQ prekeys for each device will be deleted if any had been stored.
        If present, must contain one prekey per enabled device including this one.
        Prekeys for devices that did not previously have any post-quantum prekeys stored will be silently dropped.
        Each must be accompanied by a valid signature from the new identity key in this request.""")
    @Nullable @Valid Map<Long, @NotNull @Valid @ValidPreKey(type=PreKeyType.KYBER) SignedPreKey> devicePniPqLastResortPrekeys,

    @Schema(description="the new phone-number-identity registration ID for each enabled device on the account, including this one")
    @Nullable Map<Long, Integer> pniRegistrationIds) {

  @AssertTrue
  public boolean isSignatureValidOnEachSignedPreKey() {
    List<SignedPreKey> spks = new ArrayList<>();
    if (devicePniSignedPrekeys != null) {
      spks.addAll(devicePniSignedPrekeys.values());
    }
    if (devicePniPqLastResortPrekeys != null) {
      spks.addAll(devicePniPqLastResortPrekeys.values());
    }
    return spks.isEmpty() || PreKeySignatureValidator.validatePreKeySignatures(pniIdentityKey, spks);
  }

}
