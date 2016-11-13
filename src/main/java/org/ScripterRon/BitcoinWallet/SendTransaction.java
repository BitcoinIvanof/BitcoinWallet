/**
 * Copyright 2013-2016 Ronald W Hoffman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ScripterRon.BitcoinWallet;

import org.ScripterRon.BitcoinCore.Address;
import org.ScripterRon.BitcoinCore.SerializedBuffer;
import org.ScripterRon.BitcoinCore.Sha256Hash;
import org.ScripterRon.BitcoinCore.Transaction;
import org.ScripterRon.BitcoinCore.VerificationException;

import java.io.EOFException;
import java.math.BigInteger;

/**
 * A SendTransction represents coins sent from the wallet and is created
 * from the transaction spending the coins.
 */
public class SendTransaction extends WalletTransaction {

    /** Transaction fee */
    private final BigInteger fee;

    /** Serialized transaction */
    private final byte[] txData;

    /**
     * Creates a SendTransaction
     *
     * @param       normID              Normalized transactionID
     * @param       txHash              Transaction hash
     * @param       txTime              Transaction timestamp
     * @param       blockHash           Chain block containing the transaction or null
     * @param       address             Send address (P2PKH or P2SH)
     * @param       value               Transaction value
     * @param       fee                 Transaction fee
     * @param       txData              Serialized transaction
     */
    public SendTransaction(Sha256Hash normID, Sha256Hash txHash, long txTime, Sha256Hash blockHash,
                            Address address, BigInteger value, BigInteger fee, byte[] txData) {
        super(normID, txHash, txTime, blockHash, address, value);
        this.fee = fee;
        this.txData = txData;
    }

    /**
     * Returns the transaction fee
     *
     * @return                          Fee
     */
    public BigInteger getFee() {
        return fee;
    }

    /**
     * Returns the serialized transaction
     *
     * @return                          Serialized transaction
     */
    public byte[] getTxData() {
        return txData;
    }

    /**
     * Returns the transaction
     *
     * @return                          Transaction
     * @throws      WalletException     Unable to deserialize the transaction
     */
    public Transaction getTransaction() throws WalletException {
        Transaction tx;
        try {
            SerializedBuffer inBuffer = new SerializedBuffer(txData);
            tx = new Transaction(inBuffer);
        } catch (EOFException | VerificationException exc) {
            throw new WalletException("Unable to deserialize transaction", exc);
        }
        return tx;
    }
}
