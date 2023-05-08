/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.mirror.web3.service;

import static com.hedera.mirror.common.domain.entity.EntityType.CONTRACT;
import static com.hedera.mirror.common.domain.entity.EntityType.TOKEN;
import static com.hedera.mirror.common.util.DomainUtils.fromEvmAddress;
import static com.hedera.mirror.common.util.DomainUtils.toEvmAddress;
import static com.hedera.mirror.web3.service.model.CallServiceParameters.CallType.ERROR;
import static com.hedera.mirror.web3.service.model.CallServiceParameters.CallType.ETH_CALL;
import static com.hedera.mirror.web3.service.model.CallServiceParameters.CallType.ETH_ESTIMATE_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.LOCAL_CALL_MODIFICATION_EXCEPTION;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.hedera.mirror.common.domain.token.TokenId;
import com.hedera.mirror.common.domain.token.TokenTypeEnum;
import com.hedera.mirror.web3.Web3IntegrationTest;
import com.hedera.mirror.web3.exception.InvalidTransactionException;
import com.hedera.mirror.web3.service.model.CallServiceParameters;
import com.hedera.mirror.web3.service.model.CallServiceParameters.CallType;
import com.hedera.mirror.web3.utils.FunctionEncodeDecoder;
import com.hedera.node.app.service.evm.store.models.HederaEvmAccount;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.file.Path;
import java.util.function.LongFunction;
import lombok.RequiredArgsConstructor;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.operation.CallOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@RequiredArgsConstructor(onConstructor = @__(@Autowired))
class ContractCallServiceTest extends Web3IntegrationTest {
    // The contract sources `EthCall.sol` and `Reverter.sol` are in test/resources
    @Value("classpath:contracts/EthCall/EthCall.bin")
    private Path ETH_CALL_CONTRACT_BYTES_PATH;

    @Value("classpath:contracts/Reverter/Reverter.bin")
    private Path REVERTER_CONTRACT_BYTES_PATH;

    private static final Address REVERTER_CONTRACT_ADDRESS =
            Address.fromHexString("0x00000000000000000000000000000000000004e1");

    private static final Address ETH_CALL_CONTRACT_ADDRESS =
            Address.fromHexString("0x00000000000000000000000000000000000004e9");

    private static final Address SENDER_ADDRESS = Address.fromHexString("0x00000000000000000000000000000000000003e6");

    private static final Address RECEIVER_ADDRESS = Address.fromHexString("0x00000000000000000000000000000000000003e5");

    private static final Address TOKEN_ADDRESS = Address.fromHexString("0x00000000000000000000000000000000000003e4");

    private static final String GAS_METRICS = "hedera.mirror.web3.call.gas";
    private static final LongFunction<String> hexValueOf =
            value -> Bytes.ofUnsignedLong(value).toHexString();

    private final MeterRegistry meterRegistry;
    private final ContractCallService contractCallService;
    private final FunctionEncodeDecoder encodeDecoder;

    @Test
    void pureCall() {
        final var gasUsedBeforeExecution = getGasUsedBeforeExecution(ETH_CALL);

        // multiplySimpleNumbers()
        final var pureFuncHash = "8070450f";
        final var successfulReadResponse = "0x0000000000000000000000000000000000000000000000000000000000000004";
        final var serviceParameters = serviceParameters(pureFuncHash, 0, ETH_CALL, true, ETH_CALL_CONTRACT_ADDRESS, 0);

        persistEntities(false);

        assertThat(contractCallService.processCall(serviceParameters)).isEqualTo(successfulReadResponse);

        assertGasUsedIsPositive(gasUsedBeforeExecution, ETH_CALL);
    }

    @Test
    void estimateGasForPureCall() {
        final var pureFuncHash = "8070450f";
        final var gasUsedBeforeExecution = getGasUsedBeforeExecution(ETH_ESTIMATE_GAS);
        final var expectedGasUsed = 22217L;
        final var serviceParameters =
                serviceParameters(pureFuncHash, 0, ETH_ESTIMATE_GAS, true, ETH_CALL_CONTRACT_ADDRESS, 0);

        persistEntities(false);

        assertThat(contractCallService.processCall(serviceParameters)).isEqualTo(hexValueOf.apply(expectedGasUsed));

        assertGasUsedIsPositive(gasUsedBeforeExecution, ETH_ESTIMATE_GAS);
    }

    @Test
    void viewCall() {
        final var gasUsedBeforeExecution = getGasUsedBeforeExecution(ETH_CALL);

        // returnStorageData()
        final var viewFuncHash =
                "0x6601c296000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000036b75720000000000000000000000000000000000000000000000000000000000";
        final var successfulReadResponse =
                "0x000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000047465737400000000000000000000000000000000000000000000000000000000";
        final var serviceParameters = serviceParameters(viewFuncHash, 0, ETH_CALL, true, ETH_CALL_CONTRACT_ADDRESS, 0);

        persistEntities(false);

        assertThat(contractCallService.processCall(serviceParameters)).isEqualTo(successfulReadResponse);

        assertGasUsedIsPositive(gasUsedBeforeExecution, ETH_CALL);
    }

    @Test
    void estimateGasForViewCall() {
        final var viewFuncHash =
                "0x6601c296000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000036b75720000000000000000000000000000000000000000000000000000000000";
        final var expectedGasUsed = 23340L;
        final var serviceParameters =
                serviceParameters(viewFuncHash, 0, ETH_ESTIMATE_GAS, true, ETH_CALL_CONTRACT_ADDRESS, 0);

        persistEntities(false);

        assertThat(contractCallService.processCall(serviceParameters)).isEqualTo(hexValueOf.apply(expectedGasUsed));
    }

    @Test
    void transferFunds() {
        final var gasUsedBeforeExecution = getGasUsedBeforeExecution(ETH_CALL);

        final var serviceParameters = serviceParameters("0x", 7L, ETH_CALL, true, ETH_CALL_CONTRACT_ADDRESS, 0);
        persistEntities(true);

        assertThatCode(() -> contractCallService.processCall(serviceParameters)).doesNotThrowAnyException();

        assertGasUsedIsPositive(gasUsedBeforeExecution, ETH_CALL);
    }

    @Test
    void balanceCall() {
        final var gasUsedBeforeExecution = getGasUsedBeforeExecution(ETH_CALL);

        // getAccountBalance(address)
        final var balanceCall = "0x93423e9c00000000000000000000000000000000000000000000000000000000000003e6";
        final var expectedBalance = "0x0000000000000000000000000000000000000000000000000000000000004e20";
        final var params = serviceParameters(balanceCall, 0, ETH_CALL, true, ETH_CALL_CONTRACT_ADDRESS, 0);

        persistEntities(false);

        final var isSuccessful = contractCallService.processCall(params);
        assertThat(isSuccessful).isEqualTo(expectedBalance);

        assertGasUsedIsPositive(gasUsedBeforeExecution, ETH_CALL);
    }

    @Test
    void estimateGasForBalanceCall() {
        final var gasUsedBeforeExecution = getGasUsedBeforeExecution(ETH_ESTIMATE_GAS);
        final var balanceCall = "0x93423e9c00000000000000000000000000000000000000000000000000000000000003e6";
        final var expectedGasUsed = 22715L;
        final var params =
                serviceParameters(balanceCall, 0, ETH_ESTIMATE_GAS, true, ETH_CALL_CONTRACT_ADDRESS, 15_000_000L);

        persistEntities(false);

        final var estimatedGas = contractCallService.processCall(params);
        assertThat(estimatedGas).isEqualTo(hexValueOf.apply(expectedGasUsed));

        assertGasUsedIsPositive(gasUsedBeforeExecution, ETH_ESTIMATE_GAS);
    }

    @Test
    void testRevertDetailMessage() {
        final var revertFunctionSignature = "0xa26388bb";
        final var serviceParameters =
                serviceParameters(revertFunctionSignature, 0, ETH_CALL, true, ETH_CALL_CONTRACT_ADDRESS, 0);

        persistEntities(false);

        assertThatThrownBy(() -> contractCallService.processCall(serviceParameters))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage(CONTRACT_REVERT_EXECUTED.name())
                .hasFieldOrPropertyWithValue("detail", "Custom revert message")
                .hasFieldOrPropertyWithValue(
                        "data",
                        "0x08c379a000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000015437573746f6d20726576657274206d6573736167650000000000000000000000");
    }

    @ParameterizedTest
    @EnumSource(RevertFunctions.class)
    void testReverts(final RevertFunctions revertFunctions) {
        final var serviceParameters =
                serviceParameters(revertFunctions.functionSignature, 0, ETH_CALL, true, REVERTER_CONTRACT_ADDRESS, 0);

        persistEntities(false);

        assertThatThrownBy(() -> contractCallService.processCall(serviceParameters))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage(CONTRACT_REVERT_EXECUTED.name())
                .hasFieldOrPropertyWithValue("detail", revertFunctions.errorDetail)
                .hasFieldOrPropertyWithValue("data", revertFunctions.errorData);
    }

    @Test
    void invalidFunctionSig() {
        final var gasUsedBeforeExecution = getGasUsedBeforeExecution(ERROR);

        final var wrongFunctionSignature = "0x542ec32e";
        final var serviceParameters =
                serviceParameters(wrongFunctionSignature, 0, ETH_CALL, true, ETH_CALL_CONTRACT_ADDRESS, 0);

        persistEntities(false);

        assertThatThrownBy(() -> contractCallService.processCall(serviceParameters))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage("CONTRACT_REVERT_EXECUTED")
                .hasFieldOrPropertyWithValue("data", "0x");

        assertGasUsedIsPositive(gasUsedBeforeExecution, ERROR);
    }

    @Test
    void transferNegative() {
        final var serviceParameters = serviceParameters("0x", -5L, ETH_CALL, true, ETH_CALL_CONTRACT_ADDRESS, 0);
        persistEntities(true);

        assertThatThrownBy(() -> contractCallService.processCall(serviceParameters))
                .isInstanceOf(InvalidTransactionException.class);
    }

    @Test
    void transferExceedsBalance() {
        final var serviceParameters = serviceParameters("0x", 210000L, ETH_CALL, true, ETH_CALL_CONTRACT_ADDRESS, 0);
        persistEntities(true);

        assertThatThrownBy(() -> contractCallService.processCall(serviceParameters))
                .isInstanceOf(InvalidTransactionException.class);
    }

    /**
     * _to.transfer(msg.value) fails due to the static frame,{@link CallOperation} this will be
     * supported with future release with gas_estimate support.
     */
    @Test
    void transferThruContract() {
        final var gasUsedBeforeExecution = getGasUsedBeforeExecution(ERROR);

        // transferHbarsToAddress(address)
        final var stateChangePayable = "0x80b9f03c00000000000000000000000000000000000000000000000000000000000004e6";
        final var params = serviceParameters(stateChangePayable, 90L, ETH_CALL, true, ETH_CALL_CONTRACT_ADDRESS, 0);

        persistEntities(false);

        assertThatThrownBy(() -> contractCallService.processCall(params))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage(LOCAL_CALL_MODIFICATION_EXCEPTION.toString())
                .hasFieldOrPropertyWithValue("data", "0x");

        assertGasUsedIsPositive(gasUsedBeforeExecution, ERROR);
    }

    @Test
    void estimateGasForStateChangeCall() {
        final var gasUsedBeforeExecution = getGasUsedBeforeExecution(ETH_ESTIMATE_GAS);
        final var stateChangeHash =
                "0x9ac27b62000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000033233320000000000000000000000000000000000000000000000000000000000";
        final var serviceParameters =
                serviceParameters(stateChangeHash, 0, ETH_ESTIMATE_GAS, false, ETH_CALL_CONTRACT_ADDRESS, 0);
        final var expectedGasUsed = 29601L;

        persistEntities(false);

        assertThat(contractCallService.processCall(serviceParameters)).isEqualTo(hexValueOf.apply(expectedGasUsed));

        assertGasUsedIsPositive(gasUsedBeforeExecution, ETH_ESTIMATE_GAS);
    }

    @Test
    void stateChangeFails() {
        final var gasUsedBeforeExecution = getGasUsedBeforeExecution(ERROR);

        // writeToStorageSlot(string)
        final var stateChangeHash =
                "0x9ac27b62000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000033233320000000000000000000000000000000000000000000000000000000000";
        final var serviceParameters =
                serviceParameters(stateChangeHash, 0, ETH_CALL, true, ETH_CALL_CONTRACT_ADDRESS, 0);

        persistEntities(false);

        assertThatThrownBy(() -> contractCallService.processCall(serviceParameters))
                .isInstanceOf(InvalidTransactionException.class)
                .hasFieldOrPropertyWithValue("data", "0x");

        assertGasUsedIsPositive(gasUsedBeforeExecution, ERROR);
    }

    @Test
    void estimateGasWithExactValue() {
        final var viewFuncHash =
                "0x6601c296000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000036b75720000000000000000000000000000000000000000000000000000000000";
        final var expectedAndProvidedGas = 22579L;
        final var serviceParameters = serviceParameters(
                viewFuncHash, 0, ETH_ESTIMATE_GAS, false, ETH_CALL_CONTRACT_ADDRESS, expectedAndProvidedGas);

        persistEntities(false);

        assertThat(contractCallService.processCall(serviceParameters))
                .isEqualTo(hexValueOf.apply(expectedAndProvidedGas));
    }

    @Test
    void ercPrecompileCallRevertsForEstimateGas() {
        final var tokenNameCall = "0x6f0fccab00000000000000000000000000000000000000000000000000000000000003e4";
        final var serviceParameters =
                serviceParameters(tokenNameCall, 0, ETH_ESTIMATE_GAS, false, ETH_CALL_CONTRACT_ADDRESS, 0L);

        persistEntities(false);

        assertThatThrownBy(() -> contractCallService.processCall(serviceParameters))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Precompile not supported for non-static frames");
    }

    @Test
    void precompileCallRevertsForEstimateGas() {
        final var freezeTokenCall = "0x7c93c87e00000000000000000000000000000000000000000000000000000000000003e4";
        final var serviceParameters =
                serviceParameters(freezeTokenCall, 0, ETH_ESTIMATE_GAS, false, ETH_CALL_CONTRACT_ADDRESS, 0L);

        persistEntities(false);

        assertThatThrownBy(() -> contractCallService.processCall(serviceParameters))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Precompile not supported for non-static frames");
    }

    private CallServiceParameters serviceParameters(
            String callData, long value, CallType callType, boolean isStatic, Address contract, long estimatedGas) {
        final var isGasEstimate = callType == ETH_ESTIMATE_GAS;
        final var gas = (isGasEstimate && estimatedGas > 0) ? estimatedGas : 120000L;
        final var sender = new HederaEvmAccount(SENDER_ADDRESS);
        final var data = Bytes.fromHexString(callData);
        final var receiver = callData.equals("0x") ? RECEIVER_ADDRESS : contract;

        return CallServiceParameters.builder()
                .sender(sender)
                .value(value)
                .receiver(receiver)
                .callData(data)
                .gas(gas)
                .isEstimate(isGasEstimate)
                .isStatic(isStatic)
                .callType(callType)
                .build();
    }

    private double getGasUsedBeforeExecution(final CallType callType) {
        final var callCounter = meterRegistry.find(GAS_METRICS).counters().stream()
                .filter(c -> callType.name().equals(c.getId().getTag("type")))
                .findFirst();

        var gasUsedBeforeExecution = 0d;
        if (callCounter.isPresent()) {
            gasUsedBeforeExecution = callCounter.get().count();
        }

        return gasUsedBeforeExecution;
    }

    private void assertGasUsedIsPositive(final double gasUsedBeforeExecution, final CallType callType) {
        final var afterExecution = meterRegistry.find(GAS_METRICS).counters().stream()
                .filter(c -> callType.name().equals(c.getId().getTag("type")))
                .findFirst()
                .get();

        final var gasConsumed = afterExecution.count() - gasUsedBeforeExecution;
        assertThat(gasConsumed).isPositive();
    }

    private void persistEntities(boolean isRegularTransfer) {
        final var ethCallContractBytes = encodeDecoder.getContractBytes(ETH_CALL_CONTRACT_BYTES_PATH);
        final var reverterContractBytes = encodeDecoder.getContractBytes(REVERTER_CONTRACT_BYTES_PATH);

        if (isRegularTransfer) {
            final var receiverEntityId = fromEvmAddress(RECEIVER_ADDRESS.toArrayUnsafe());
            final var receiverEvmAddress = toEvmAddress(receiverEntityId);

            domainBuilder
                    .entity()
                    .customize(e -> e.id(receiverEntityId.getId())
                            .num(receiverEntityId.getEntityNum())
                            .evmAddress(receiverEvmAddress)
                            .type(CONTRACT))
                    .persist();
        }
        final var contractEntityId = fromEvmAddress(ETH_CALL_CONTRACT_ADDRESS.toArrayUnsafe());
        final var contractEvmAddress = toEvmAddress(contractEntityId);

        domainBuilder
                .entity()
                .customize(e -> e.id(contractEntityId.getId())
                        .num(contractEntityId.getEntityNum())
                        .evmAddress(contractEvmAddress)
                        .type(CONTRACT)
                        .balance(1500L))
                .persist();

        domainBuilder
                .contract()
                .customize(c -> c.id(contractEntityId.getId()).runtimeBytecode(ethCallContractBytes))
                .persist();

        domainBuilder
                .contractState()
                .customize(c -> c.contractId(contractEntityId.getId())
                        .slot(Bytes.fromHexString("0x0000000000000000000000000000000000000000000000000000000000000000")
                                .toArrayUnsafe())
                        .value(Bytes.fromHexString("0x4746573740000000000000000000000000000000000000000000000000000000")
                                .toArrayUnsafe()))
                .persist();

        domainBuilder
                .recordFile()
                .customize(f -> f.bytes(ethCallContractBytes))
                .persist();

        final var revertContractEntityId = fromEvmAddress(REVERTER_CONTRACT_ADDRESS.toArrayUnsafe());
        final var revertContractEvmAddress = toEvmAddress(revertContractEntityId);

        domainBuilder
                .entity()
                .customize(e -> e.id(revertContractEntityId.getId())
                        .num(revertContractEntityId.getEntityNum())
                        .evmAddress(revertContractEvmAddress)
                        .type(CONTRACT)
                        .balance(1500L))
                .persist();

        domainBuilder
                .contract()
                .customize(c ->
                        c.id(revertContractEntityId.getId()).runtimeBytecode(reverterContractBytes))
                .persist();

        final var senderEntityId = fromEvmAddress(SENDER_ADDRESS.toArrayUnsafe());
        final var senderEvmAddress = toEvmAddress(senderEntityId);

        domainBuilder
                .entity()
                .customize(e -> e.id(senderEntityId.getId())
                        .num(senderEntityId.getEntityNum())
                        .evmAddress(senderEvmAddress)
                        .balance(20000L))
                .persist();

        final var tokenEntityId = fromEvmAddress(TOKEN_ADDRESS.toArrayUnsafe());
        final var tokenEvmAddress = toEvmAddress(tokenEntityId);

        domainBuilder
                .entity()
                .customize(e -> e.id(tokenEntityId.getId())
                        .num(tokenEntityId.getEntityNum())
                        .evmAddress(tokenEvmAddress)
                        .type(TOKEN)
                        .balance(1500L))
                .persist();

        domainBuilder
                .token()
                .customize(t -> t.tokenId(new TokenId(tokenEntityId)).type(TokenTypeEnum.FUNGIBLE_COMMON))
                .persist();
    }

    @RequiredArgsConstructor
    private enum RevertFunctions {
        REVERT_WITH_CUSTOM_ERROR_PURE("revertWithCustomErrorPure", "35314694", "", "0x0bd3d39c"),
        REVERT_WITH_PANIC_PURE(
                "revertWithPanicPure",
                "83889056",
                "",
                "0x4e487b710000000000000000000000000000000000000000000000000000000000000012"),
        REVERT_PAYABLE(
                "revertPayable",
                "d0efd7ef",
                "RevertReasonPayable",
                "0x08c379a000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000013526576657274526561736f6e50617961626c6500000000000000000000000000"),
        REVERT_PURE(
                "revertPure",
                "b2e0100c",
                "RevertReasonPure",
                "0x08c379a000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000010526576657274526561736f6e5075726500000000000000000000000000000000"),
        REVERT_VIEW(
                "revertView",
                "90e9b875",
                "RevertReasonView",
                "0x08c379a000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000010526576657274526561736f6e5669657700000000000000000000000000000000"),
        REVERT_WITH_CUSTOM_ERROR("revertWithCustomError", "46fc4bb1", "", "0x0bd3d39c"),
        REVERT_WITH_NOTHING("revertWithNothing", "fe0a3dd7", "", "0x"),
        REVERT_WITH_NOTHING_PURE("revertWithNothingPure", "2dac842f", "", "0x"),
        REVERT_WITH_PANIC(
                "revertWithPanic",
                "33fe3fbd",
                "",
                "0x4e487b710000000000000000000000000000000000000000000000000000000000000012"),
        REVERT_WITH_STRING(
                "revertWithString",
                "0323d234",
                "Some revert message",
                "0x08c379a000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000013536f6d6520726576657274206d65737361676500000000000000000000000000"),
        REVERT_WITH_STRING_PURE(
                "revertWithStringPure",
                "8b153371",
                "Some revert message",
                "0x08c379a000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000013536f6d6520726576657274206d65737361676500000000000000000000000000"),
        REVERT_WITH_CUSTOM_ERROR_WITH_PARAMETERS(
                "revertWithCustomErrorWithParameters",
                "86451c2b",
                "",
                "0xcc4263a0000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000013536f6d6520726576657274206d65737361676500000000000000000000000000"),
        REVERT_WITH_CUSTOM_ERROR_WITH_PARAMETERS_PURE(
                "revertWithCustomErrorWithParameters",
                "b1c5ae51",
                "",
                "0xcc4263a0000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000013536f6d6520726576657274206d65737361676500000000000000000000000000");

        private final String name;
        private final String functionSignature;
        private final String errorDetail;
        private final String errorData;
    }
}