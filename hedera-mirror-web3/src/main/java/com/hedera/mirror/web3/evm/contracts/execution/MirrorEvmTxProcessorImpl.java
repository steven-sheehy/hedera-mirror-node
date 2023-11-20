/*
 * Copyright (C) 2022-2023 Hedera Hashgraph, LLC
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

package com.hedera.mirror.web3.evm.contracts.execution;

import com.hedera.mirror.web3.common.ContractCallContext;
import com.hedera.mirror.web3.evm.account.MirrorEvmContractAliases;
import com.hedera.mirror.web3.evm.contracts.execution.traceability.MirrorOperationTracer;
import com.hedera.mirror.web3.evm.store.Store;
import com.hedera.mirror.web3.exception.MirrorEvmTransactionException;
import com.hedera.mirror.web3.service.model.CallServiceParameters;
import com.hedera.node.app.service.evm.contracts.execution.BlockMetaSource;
import com.hedera.node.app.service.evm.contracts.execution.EvmProperties;
import com.hedera.node.app.service.evm.contracts.execution.HederaEvmTransactionProcessingResult;
import com.hedera.node.app.service.evm.contracts.execution.HederaEvmTxProcessor;
import com.hedera.node.app.service.evm.contracts.execution.PricesAndFeesProvider;
import com.hedera.node.app.service.evm.store.contracts.AbstractCodeCache;
import com.hedera.node.app.service.evm.store.contracts.HederaEvmMutableWorldState;
import com.hedera.services.store.models.Account;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.time.Instant;
import java.util.Map;
import javax.inject.Provider;
import org.apache.commons.lang3.StringUtils;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;

public class MirrorEvmTxProcessorImpl extends HederaEvmTxProcessor implements MirrorEvmTxProcessor {

    private final AbstractCodeCache codeCache;
    private final MirrorEvmContractAliases aliasManager;
    private final Store store;

    @SuppressWarnings("java:S107")
    public MirrorEvmTxProcessorImpl(
            final HederaEvmMutableWorldState worldState,
            final PricesAndFeesProvider pricesAndFeesProvider,
            final EvmProperties dynamicProperties,
            final GasCalculator gasCalculator,
            final Map<String, Provider<MessageCallProcessor>> mcps,
            final Map<String, Provider<ContractCreationProcessor>> ccps,
            final BlockMetaSource blockMetaSource,
            final MirrorEvmContractAliases aliasManager,
            final AbstractCodeCache codeCache,
            final MirrorOperationTracer operationTracer,
            final Store store) {
        super(worldState, pricesAndFeesProvider, dynamicProperties, gasCalculator, mcps, ccps, blockMetaSource);

        super.setOperationTracer(operationTracer);
        this.aliasManager = aliasManager;
        this.codeCache = codeCache;
        this.store = store;
    }

    public HederaEvmTransactionProcessingResult execute(CallServiceParameters params, long estimatedGas) {
        final long gasPrice = gasPriceTinyBarsGiven(Instant.now(), true);
        // in cases where the receiver is the zero address, we know it's a contract create scenario
        super.setupFields(params.getReceiver().equals(Address.ZERO));
        final var contractCallContext = ContractCallContext.get();
        contractCallContext.setCreate(Address.ZERO.equals(params.getReceiver()));
        contractCallContext.setEstimate(params.isEstimate());

        store.wrap();
        if (params.isEstimate()) {
            final var defaultAccount = Account.getDefaultAccount();
            store.updateAccount(defaultAccount);
        }

        return super.execute(
                params.getSender(),
                params.getReceiver(),
                gasPrice,
                params.isEstimate() ? estimatedGas : params.getGas(),
                params.getValue(),
                params.getCallData(),
                params.isStatic(),
                aliasManager.resolveForEvm(params.getReceiver()));
    }

    @SuppressWarnings("java:S5411")
    @Override
    protected HederaFunctionality getFunctionType() {
        return ContractCallContext.get().isCreate()
                ? HederaFunctionality.ContractCreate
                : HederaFunctionality.ContractCall;
    }

    @Override
    protected MessageFrame buildInitialFrame(
            final MessageFrame.Builder baseInitialFrame, final Address to, final Bytes payload, long value) {
        if (ContractCallContext.get().isCreate()) {
            return baseInitialFrame
                    .type(MessageFrame.Type.CONTRACT_CREATION)
                    .address(to)
                    .contract(to)
                    .inputData(Bytes.EMPTY)
                    .code(CodeFactory.createCode(payload, 0, false))
                    .build();
        } else {
            final var resolvedForEvm = aliasManager.resolveForEvm(to);
            final var code = aliasManager.isMirror(resolvedForEvm) ? codeCache.getIfPresent(resolvedForEvm) : null;

            // If there is no bytecode, it means we have a non-token and non-contract account,
            // hence the code should be null and there must be a value transfer.
            if (code == null && value <= 0) {
                throw new MirrorEvmTransactionException(
                        ResponseCodeEnum.INVALID_TRANSACTION, StringUtils.EMPTY, StringUtils.EMPTY);
            }

            return baseInitialFrame
                    .type(MessageFrame.Type.MESSAGE_CALL)
                    .address(to)
                    .contract(to)
                    .inputData(payload)
                    .code(code == null ? CodeV0.EMPTY_CODE : code)
                    .build();
        }
    }
}