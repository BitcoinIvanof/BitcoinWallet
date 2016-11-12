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
import org.ScripterRon.BitcoinCore.BlockHeader;
import org.ScripterRon.BitcoinCore.ECKey;
import org.ScripterRon.BitcoinCore.Sha256Hash;
import org.ScripterRon.BitcoinCore.VerificationException;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>A Wallet stores block headers, transactions, addresses and keys.  These are used to
 * access bitcoins recorded in the block chain.  A wallet can be deleted and recreated as long
 * as the private keys have been exported and then imported into the new wallet.</p>
 */
public abstract class Wallet {

    /** Block chain checkpoints */
    protected static final Map<Integer, Sha256Hash> checkpoints = new HashMap<>();
    static {
        checkpoints.put(50000,
                        new Sha256Hash("000000001aeae195809d120b5d66a39c83eb48792e068f8ea1fea19d84a4278a"));
        checkpoints.put(75000,
                        new Sha256Hash("00000000000ace2adaabf1baf9dc0ec54434db11e9fd63c1819d8d77df40afda"));
        checkpoints.put(100000,
                        new Sha256Hash("000000000003ba27aa200b1cecaad478d2b00432346c3f1f3986da1afd33e506"));
        checkpoints.put(125000,
                        new Sha256Hash("00000000000042391c3620056af66ca9ad7cb962424a9b34611915cebb9e1a2a"));
        checkpoints.put(150000,
                        new Sha256Hash("0000000000000a3290f20e75860d505ce0e948a1d1d846bec7e39015d242884b"));
        checkpoints.put(175000,
                        new Sha256Hash("00000000000006b975c097e9a5235de03d9024ddb205fd24dfcd508403fa907c"));
        checkpoints.put(200000,
                        new Sha256Hash("000000000000034a7dedef4a161fa058a2d67a173a90155f3a2fe6fc132e0ebf"));
        checkpoints.put(225000,
                        new Sha256Hash("000000000000013d8781110987bf0e9f230e3cc85127d1ee752d5dd014f8a8e1"));
        checkpoints.put(250000,
                        new Sha256Hash("000000000000003887df1f29024b06fc2200b55f8af8f35453d7be294df2d214"));
        checkpoints.put(275000,
                        new Sha256Hash("00000000000000044750d80a0d3f3e307e54e8802397ae840d91adc28068f5bc"));
        checkpoints.put(300000,
                        new Sha256Hash("000000000000000082ccf8f1557c5d40b21edabb18d2d691cfbf87118bac7254"));
        checkpoints.put(325000,
                        new Sha256Hash("00000000000000000409695bce21828b31a7143fa35fcab64670dd337a71425d"));
        checkpoints.put(350000,
                        new Sha256Hash("0000000000000000053cf64f0400bb38e0c4b3872c38795ddde27acb40a112bb"));
        checkpoints.put(375000,
                        new Sha256Hash("000000000000000009733ff8f11fbb9575af7412df3fae97f382376709c965dc"));
        checkpoints.put(400000,
                        new Sha256Hash("000000000000000004ec466ce4732fe6f1ed1cddc2ed4b328fff5224276e3f6f"));
        checkpoints.put(425000,
                        new Sha256Hash("00000000000000000142adfebcb9a0aa75f0c4980dd5c7dd17062bf7de77c16d"));
    }

    /** Database update lock */
    protected final Object lock = new Object();

    /** Current chain head */
    protected Sha256Hash chainHead;

    /** Current chain height */
    protected int chainHeight;

    /** Current chain work */
    protected BigInteger chainWork;

    /** Application data path */
    protected final String dataPath;

    /**
     * Creates a Wallet
     * @param       dataPath            Application data path
     * @throws      WalletException     Unable to initialize the database
     */
    public Wallet(String dataPath) throws WalletException {
        this.dataPath = dataPath;
    }

    /**
     * Returns the chain height
     *
     * @return                          Chain height
     */
    public int getChainHeight() {
        return chainHeight;
    }

    /**
     * Returns the chain head
     *
     * @return                          Chain head
     */
    public Sha256Hash getChainHead() {
        return chainHead;
    }

    /**
     * Returns the chain work
     *
     * @return                          Chain work
     */
    public BigInteger getChainWork() {
        return chainWork;
    }

    /**
     * Returns the chain height of the latest block earlier than the requested time.
     *
     * @param       rescanTime          Block chain rescan time
     * @return                          Block height or 0 if no block meets the criteria
     * @throws      WalletException     Unable to get the chain height
     */
    public abstract int getRescanHeight(long rescanTime) throws WalletException;

    /**
     * Returns the block hash for the block at the requested height
     *
     * @param       blockHeight         Block height
     * @return                          Block Hash or null if block not found
     * @throws      WalletException     Unable to get block
     */
    public abstract Sha256Hash getBlockHash(int blockHeight) throws WalletException;

    /**
     * Returns the chain list from the block following the start block up to the stop
     * block.  A maximum of 500 blocks will be returned.
     *
     * @param       startHeight         Start block height
     * @param       stopBlock           Stop block
     * @return                          Block hash list
     * @throws      WalletException     Unable to get blocks from database
     */
    public abstract List<Sha256Hash> getChainList(int startHeight, Sha256Hash stopBlock) throws WalletException;

    /**
     * Stores an address
     *
     * @param       address             Address
     * @throws      WalletException     Unable to store the address
     */
    public abstract void storeAddress(Address address) throws WalletException;

    /**
     * Sets the address label
     *
     * @param       address             Address
     * @throws      WalletException     Unable to update label
     */
    public abstract void setAddressLabel(Address address) throws WalletException;

    /**
     * Deletes an address
     *
     * @param       address             Address
     * @throws      WalletException     Unable to delete address
     */
    public abstract void deleteAddress(Address address) throws WalletException;

    /**
     * Returns a list of all addresses sorted by the label
     *
     * @return                          List of addresses stored in the database
     * @throws      WalletException     Unable to get address list
     */
    public abstract List<Address> getAddressList() throws WalletException;

    /**
     * Stores a key
     *
     * @param       key                 Public/private key pair
     * @throws      WalletException     Unable to store the key
     */
    public abstract void storeKey(ECKey key) throws WalletException;

    /**
     * Sets the key label
     *
     * @param       key                 Public/private key pair
     * @throws      WalletException     Unable to update the label
     */
    public abstract void setKeyLabel(ECKey key) throws WalletException;

    /**
     * Returns a list of all keys sorted by the label
     *
     * @return                          List of keys stored in the database
     * @throws      KeyException        Private key does not match public key
     * @throws      WalletException     Unable to get address list
     */
    public abstract List<ECKey> getKeyList() throws KeyException, WalletException;

    /**
     * Checks if this is a new block
     *
     * @param       blockHash           Block hash
     * @return                          TRUE if this is a new block
     * @throws      WalletException     Unable to check block status
     */
    public abstract boolean isNewBlock(Sha256Hash blockHash) throws WalletException;

    /**
     * Stores a block header
     *
     * @param       storedHeader        Block header
     * @throws      WalletException     Unable to store the block header
     */
    public abstract void storeHeader(StoredHeader storedHeader) throws WalletException;

    /**
     * Updates the matched transactions for a block
     *
     * @param       header              Block Header
     * @throws      WalletException     Unable to update the database
     */
    public abstract void updateMatches(BlockHeader header) throws WalletException;

    /**
     * Returns a block header stored in the database
     *
     * @param       blockHash           Block hash
     * @return                          Block header or null if the block is not found
     * @throws      WalletException     Unable to retrieve the block header
     */
    public abstract StoredHeader getHeader(Sha256Hash blockHash) throws WalletException;

    /**
     * Returns the block header for the child of the specified block
     *
     * @param       parentHash          Parent block hash
     * @return                          Child block header or null if no child is found
     * @throws      WalletException     Unable to retrieve the child block header
     */
    public abstract StoredHeader getChildHeader(Sha256Hash parentHash) throws WalletException;

    /**
     * Checks if this is a new transaction
     *
     * @param       txHash              Transaction hash
     * @return                          TRUE if this is a new transaction
     * @throws      WalletException     Unable to check transaction status
     */
    public abstract boolean isNewTransaction(Sha256Hash txHash) throws WalletException;

    /**
     * Store a receive transaction
     *
     * @param       receiveTx           Receive transaction
     * @throws      WalletException     Unable to store the transaction
     */
    public abstract void storeReceiveTx(ReceiveTransaction receiveTx) throws WalletException;

    /**
     * Updates the spent status for a receive transaction
     *
     * @param       txHash              Transaction hash
     * @param       txIndex             Transaction output index
     * @param       isSpent             TRUE if the transaction output has been spent
     * @throws      WalletException     Unable to update transaction status
     */
    public abstract void setTxSpent(Sha256Hash txHash, int txIndex, boolean isSpent) throws WalletException;

    /**
     * Updates the safe status for a receive transaction
     *
     * @param       txHash              Transaction hash
     * @param       txIndex             Transaction output index
     * @param       inSafe              TRUE if the transaction output is in the safe
     * @throws      WalletException     Unable to update transaction status
     */
    public abstract void setTxSafe(Sha256Hash txHash, int txIndex, boolean inSafe) throws WalletException;

    /**
     * Updates the delete status for a receive transaction
     *
     * @param       txHash              Transaction hash
     * @param       txIndex             Transaction output index
     * @param       isDeleted           TRUE if the transaction output is deleted
     * @throws      WalletException     Unable to update transaction status
     */
    public abstract void setReceiveTxDelete(Sha256Hash txHash, int txIndex, boolean isDeleted) throws WalletException;

    /**
     * Returns a list of all receive transactions that have not been deleted.  If we have multiple
     * transactions with the same normalized ID, we will return the one that has been confirmed.
     * If none of them are confirmed, we will return the first one we encounter.
     *
     * @return                          List of receive transactions
     * @throws      WalletException     Unable to get transaction list
     */
    public abstract List<ReceiveTransaction> getReceiveTxList() throws WalletException;

    /**
     * Store a send transaction
     *
     * @param       sendTx              Send transaction
     * @throws      WalletException     Unable to store the transaction
     */
    public abstract void storeSendTx(SendTransaction sendTx) throws WalletException;

    /**
     * Updates the delete status for a send transaction
     *
     * @param       txHash              Transaction hash
     * @param       isDeleted           TRUE if the transaction is deleted
     * @throws      WalletException     Unable to update transaction status
     */
    public abstract void setSendTxDelete(Sha256Hash txHash, boolean isDeleted) throws WalletException;

    /**
     * Returns the requested send transaction
     *
     * @param       txHash              Send transaction hash
     * @return                          Transaction or null if not found or is deleted
     * @throws      WalletException     Unable to get the transaction from the database
     */
    public abstract SendTransaction getSendTx(Sha256Hash txHash) throws WalletException;

    /**
     * Returns a list of all send transactions that have not been deleted.  If we have multiple
     * transactions with the same normalized ID, we will return the one that has been confirmed.
     * If none of them are confirmed, we will return the first one we encounter.
     *
     * @return                          List of send transactions
     * @throws      WalletException     Unable to get transaction list
     */
    public abstract List<SendTransaction> getSendTxList() throws WalletException;

    /**
     * Returns the transaction depth.  This is the number of blocks in the chain
     * including the block containing the transaction.  So a depth of 0 indicates
     * the transaction has not been confirmed, a depth of 1 indicates just the
     * block containing the transaction is on the chain, etc.
     *
     * @param       txHash                  Transaction hash
     * @return                              Confirmation depth
     * @throws      WalletException         Unable to get transaction depth
     */
    public abstract int getTxDepth(Sha256Hash txHash) throws WalletException;

    /**
     * Deletes all transactions later than the rescan time.  Transactions that are not
     * in a block will not be deleted.
     *
     * @param       rescanTime              Rescan time in seconds
     * @throws      WalletException         Unable to delete transactions
     */
    public abstract void deleteTransactions(long rescanTime) throws WalletException;

    /**
     * Locates the junction where the chain represented by the specified block joins
     * the current block chain.  The returned list starts with the junction block
     * and contains all blocks in the chain leading to the specified block.
     *
     * A BlockNotFoundException will be thrown if the chain cannot be resolved because a
     * block is missing.  The caller should get the block from a peer, store it in the
     * database and then retry.
     *
     * @param       chainHash               The block hash of the chain head
     * @return                              List of blocks in the chain leading to the new head
     * @throws      BlockNotFoundException  A block in the chain was not found
     * @throws      WalletException         Unable to get blocks from the database
     */
    public abstract List<StoredHeader> getJunction(Sha256Hash chainHash)
                                throws BlockNotFoundException, WalletException;

    /**
     * Changes the chain head and updates all blocks from the junction block up to the new
     * chain head.  The junction block is the point where the current chain and the new
     * chain intersect.  A VerificationException will be thrown if the new chain head is
     * for a checkpoint block and the block hash doesn't match the checkpoint hash.
     *
     * @param       chainList               List of all chain blocks starting with the junction block
     *                                      up to and including the new chain head
     * @throws      VerificationException   Chain verification failed
     * @throws      WalletException         Unable to update the database
     */
    public abstract void setChainHead(List<StoredHeader> chainList) throws WalletException, VerificationException;

    /**
     * Closes the database
     */
    public abstract void close();
}
