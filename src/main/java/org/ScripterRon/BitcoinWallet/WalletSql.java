/*
 * Copyright 2014-2017 Ronald Hoffman.
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
package org.ScripterRon.BitcoinWallet;
import static org.ScripterRon.BitcoinWallet.Main.log;

import org.ScripterRon.BitcoinCore.Address;
import org.ScripterRon.BitcoinCore.BlockHeader;
import org.ScripterRon.BitcoinCore.ECException;
import org.ScripterRon.BitcoinCore.ECKey;
import org.ScripterRon.BitcoinCore.EncryptedPrivateKey;
import org.ScripterRon.BitcoinCore.NetParams;
import org.ScripterRon.BitcoinCore.RejectMessage;
import org.ScripterRon.BitcoinCore.Sha256Hash;
import org.ScripterRon.BitcoinCore.VerificationException;

import java.io.EOFException;
import java.io.File;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * <p>A Wallet stores block headers, transactions, addresses and keys.  These are used to
 * access Bitcoins recorded in the block chain.  A wallet can be deleted and recreated as long
 * as the private keys have been exported and then imported into the new wallet.</p>
 */
public class WalletSql extends Wallet {

    /** Settings table definition */
    private static final String Settings_Table = "CREATE TABLE IF NOT EXISTS Settings ("
            + "schema_name          VARCHAR(32) NOT NULL,"          // Database schema name
            + "schema_version       SMALLINT NOT NULL)";            // Database schema version

    /** Headers table definitions */
    private static final String Headers_Table = "CREATE TABLE IF NOT EXISTS Headers ("
            + "db_id                IDENTITY,"                      // Row identity
            + "block_hash_index     BIGINT NOT NULL,"               // Block hash index
            + "block_hash           BINARY NOT NULL,"               // Block hash
            + "prev_hash_index      BIGINT NOT NULL,"               // Previous block hash index
            + "Prev_hash            BINARY NOT NULL,"               // Previous block hash
            + "version              INTEGER NOT NULL,"              // Block version
            + "timestamp            BIGINT NOT NULL,"               // Block timestamp
            + "target_difficulty    BIGINT NOT NULL,"               // Block target difficulty
            + "merkle_root          BINARY NOT NULL,"               // Block merkle root
            + "block_height         INTEGER NOT NULL,"              // Block height or -1
            + "chain_work           BINARY NOT NULL,"               // Chain work
            + "matches              BINARY)";                       // Transactin matches or null
    private static final String Headers_IX1 = "CREATE INDEX IF NOT EXISTS Headers_IX1 ON Headers(block_hash_index)";
    private static final String Headers_IX2 = "CREATE INDEX IF NOT EXISTS Headers_IX2 ON Headers(prev_hash_index)";
    private static final String Headers_IX3 = "CREATE INDEX IF NOT EXISTS Headers_IX3 ON Headers(block_height)";

    /** Received table definitions */
    private static final String Received_Table = "CREATE TABLE IF NOT EXISTS Received ("
            + "db_id                IDENTITY,"                      // Row identity
            + "tx_hash_index        BIGINT NOT NULL,"               // Transaction hash index
            + "tx_hash              BINARY NOT NULL,"               // Transaction hash
            + "tx_index             SMALLINT NOT NULL,"             // Transaction output index
            + "norm_hash            BINARY NOT NULL,"               // Normalized transaction hash
            + "timestamp            BIGINT NOT NULL,"               // Transaction timestamp
            + "block_hash           BINARY,"                        // Block containing the transaction or null
            + "address              BINARY NOT NULL,"               // Recipient address
            + "value                BIGINT NOT NULL,"               // Transaction value
            + "script_bytes         BINARY NOT NULL,"               // Transaction output script bytes
            + "is_spent             BOOLEAN NOT NULL,"              // Transaction output is spent
            + "is_change            BOOLEAN NOT NULL,"              // Address is a change address
            + "in_safe              BOOLEAN NOT NULL,"              // Transaction output is in the safe
            + "is_coinbase          BOOLEAN NOT NULL,"              // Transaction is coinbase transaction
            + "is_deleted           BOOLEAN NOT NULL)";             // Transaction output is deleted

    private static final String Received_IX1 = "CREATE INDEX IF NOT EXISTS Received_IX1 ON Received(tx_hash_index)";

    /** Sent table definitions */
    private static final String Sent_Table = "CREATE TABLE IF NOT EXISTS Sent ("
            + "db_id                IDENTITY,"                      // Row identity
            + "tx_hash_index        BIGINT NOT NULL,"               // Transaction hash index
            + "tx_hash              BINARY NOT NULL,"               // Transaction hash
            + "norm_hash            BINARY NOT NULL,"               // Normalized transaction hash
            + "timestamp            BIGINT NOT NULL,"               // Transaction timestamp
            + "block_hash           BINARY,"                        // Block containing the transaction or null
            + "address_type         TINYINT,"                       // Address type
            + "address              BINARY NOT NULL,"               // Recipient address hash
            + "value                BIGINT NOT NULL,"               // Transaction value
            + "fee                  BIGINT NOT NULL,"               // Transaction fee
            + "is_deleted           BOOLEAN NOT NULL,"              // Transaction is deleted
            + "tx_data              BINARY NOT NULL)";              // Transaction data
    private static final String Sent_IX1 = "CREATE UNIQUE INDEX IF NOT EXISTS Sent_IX1 ON Sent(tx_hash_index)";

    /** Addresses table definitions */
    private static final String Addresses_Table = "CREATE TABLE IF NOT EXISTS Addresses ("
            + "db_id                IDENTITY,"                      // Row identity
            + "type                 TINYINT,"                       // Address type
            + "address              BINARY NOT NULL,"               // Address hash
            + "label                VARCHAR)";                      // Associated label or null

    /** Keys table definitions */
    private static final String Keys_Table = "CREATE TABLE IF NOT EXISTS Keys ("
            + "db_id                IDENTITY,"                      // Row identity
            + "public_key           BINARY NOT NULL,"               // Public key
            + "private_key          BINARY NOT NULL,"               // Encrypted private key
            + "timestamp            BIGINT NOT NULL,"               // Time key created
            + "label                VARCHAR,"                       // Associated label or null
            + "is_change            BOOLEAN NOT NULL)";             // Is a change key

    /** Database schema name */
    public static final String schemaName = "BitcoinWallet Block Store";

    /** Database schema version */
    public static final int schemaVersion = 104;

    /** Per-thread database connection */
    private final ThreadLocal<Connection> threadConnection = new ThreadLocal<>();

    /** List of all database connections */
    private final List<Connection> allConnections = Collections.synchronizedList(new ArrayList<>());

    /** Database connection URL */
    private final String connectionURL;

    /**
     * Create the Wallet
     *
     * @param       dataPath                Application data path
     * @throws      WalletException         Unable to initialize the database
     */
    public WalletSql(String dataPath) throws WalletException {
        super(dataPath);
        File databaseDir = new File(String.format("%s%sDatabase", dataPath, Main.fileSeparator));
        if (!databaseDir.exists())
            databaseDir.mkdirs();
        long maxMemory = Runtime.getRuntime().maxMemory()/(1024*1024);
        long dbCacheSize;
        if (maxMemory < 256)
            dbCacheSize = 64;
        else if (maxMemory < 384)
            dbCacheSize = 128;
        else
            dbCacheSize = 256;
        String databasePath = dataPath.replace('\\', '/');
        connectionURL = String.format("jdbc:h2:%s/Database/bitcoin;CACHE_SIZE=%d",
                                      databasePath, dbCacheSize*1024);
        log.info("Database connection URL: "+connectionURL);
        //
        // Load the JDBC driver
        //
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException exc) {
            log.error("Unable to load the JDBC driver", exc);
            throw new WalletException("Unable to load the JDBC driver", exc);
        }
        //
        // Initialize the database
        //
        if (tableExists("Settings")) {
            getSettings();
        } else {
            createTables();
            initTables();
        }
    }

    /**
     * Close the database
     */
    @Override
    public void close() {
        int index = 0;
        for (Connection conn : allConnections) {
            index++;
            try {
                conn.close();
                log.info(String.format("Database connection %d closed", index));
            } catch (SQLException exc) {
                log.error(String.format("SQL error while closing connection %d", index), exc);
            }
        }
        allConnections.clear();
    }

    /**
     * Get the database connection for the current thread
     *
     * @return                              Connection for the current thread
     * @throws      WalletException         Unable to obtain a database connection
     */
    private Connection getConnection() throws WalletException {
        Connection conn;
        synchronized (lock) {
            try {
                conn = threadConnection.get();
                if (conn == null || conn.isClosed()) {
                    threadConnection.set(DriverManager.getConnection(connectionURL, "SCRIPTERRON", "Bitcoin"));
                    conn = threadConnection.get();
                    allConnections.add(conn);
                    log.info(String.format("Database connection %d created", allConnections.size()));
                }
            } catch (SQLException exc) {
                log.error(String.format("Unable to connect to SQL database %s", connectionURL), exc);
                throw new WalletException("Unable to connect to SQL database");
            }
        }
        return conn;
    }

    /**
     * Rollback the current transaction and turn auto commit back on
     *
     * @param       stmt            Statement to be closed or null
     */
    private void rollback(AutoCloseable... stmts) {
        try {
            Connection conn = getConnection();
            for (AutoCloseable stmt : stmts)
                if (stmt != null)
                    stmt.close();
            conn.rollback();
            conn.setAutoCommit(true);
        } catch (Exception exc) {
            log.error("Unable to rollback transaction", exc);
        }
    }

    /**
     * Get the hash index for a SHA-256 hash
     *
     * @param       hash                SHA-256 hash
     * @return                          Hash index
     */
    private long getHashIndex(Sha256Hash hash) {
        byte[] bytes = hash.getBytes();
        return (((long)bytes[24]&0xffL)<<56) | (((long)bytes[25]&0xffL)<<48) |
                        (((long)bytes[26]&0xffL)<<40) | (((long)bytes[27]&0xffl)<<32) |
                        (((long)bytes[28]&0xffL)<<24) | (((long)bytes[29]&0xffL)<<16) |
                        (((long)bytes[30]&0xffL)<<8)  | ((long)bytes[31]&0xffL);
    }

    /**
     * Get the serialized matching transactions
     *
     * @param       matches             Matches transactions
     * @return                          Serialized byte array or null if no matches
     */
    private byte[] getMatches(List<Sha256Hash> matches) {
        if (matches == null || matches.isEmpty())
            return null;
        byte[] bytes = new byte[matches.size()*32];
        int offset = 0;
        for (Sha256Hash txHash : matches) {
            System.arraycopy(txHash.getBytes(), 0, bytes, offset, 32);
            offset += 32;
        }
        return bytes;
    }

    /**
     * Get the matching transaction from the serialized byte array
     *
     * @param       bytes               Serialized byte stream
     * @return                          List of matching transactions or null if there are no matches
     */
    private List<Sha256Hash> getMatches(byte[] bytes) {
        if (bytes == null || bytes.length == 0)
            return null;
        List<Sha256Hash> matches = new ArrayList<>(bytes.length/32);
        for (int offset=0; offset<bytes.length; offset+=32)
            matches.add(new Sha256Hash(bytes, offset, 32));
        return matches;
    }

    /**
     * Checks if a table exists
     *
     * @param       table               Table name
     * @return                          TRUE if the table exists
     * @throws      WalletException     Unable to access the database server
     */
    private boolean tableExists(String table) throws WalletException {
        boolean tableExists;
        Connection conn = getConnection();
        try (Statement s = conn.createStatement()) {
            s.executeQuery("SELECT 1 FROM "+table+" WHERE 1 = 2");
            tableExists = true;
        } catch (SQLException exc) {
            tableExists = false;
        }
        return tableExists;
    }

    /**
     * Create the database tables
     *
     * @throws      WalletException     Unable to create database tables
     */
    private void createTables() throws WalletException {
        Connection conn = getConnection();
        try (Statement s = conn.createStatement()) {
            //
            // Create the tables
            //
            s.executeUpdate(Settings_Table);
            s.executeUpdate(Headers_Table);
            s.executeUpdate(Headers_IX1);
            s.executeUpdate(Headers_IX2);
            s.executeUpdate(Headers_IX3);
            s.executeUpdate(Received_Table);
            s.executeUpdate(Received_IX1);
            s.executeUpdate(Sent_Table);
            s.executeUpdate(Sent_IX1);
            s.executeUpdate(Addresses_Table);
            s.executeUpdate(Keys_Table);
            log.info("SQL database tables created");
        } catch (SQLException exc) {
            log.error("Unable to create SQL database tables", exc);
            throw new WalletException("Unable to create SQL database tables");
        }
    }

    /**
     * Initialize the tables
     *
     * @throws      WalletException     Unable to initialize the database tables
     */
    private void initTables() throws WalletException {
        Connection conn = getConnection();
         try {
            conn.setAutoCommit(false);
            chainHead = new Sha256Hash(NetParams.GENESIS_BLOCK_HASH);
            chainHeight = 0;
            chainWork = BigInteger.ONE;
            try (PreparedStatement s1 = conn.prepareStatement("INSERT INTO Headers "
                        + "(block_hash_index,block_hash,prev_hash_index,prev_hash,version,timestamp,target_difficulty,"
                        + "merkle_root,block_height,chain_work) VALUES(?,?,0,?,?,?,?,?,0,?)");
                PreparedStatement s2 = conn.prepareStatement("INSERT INTO Settings "
                        + "(schema_name,schema_version) VALUES(?,?)")) {
                //
                // Add the genesis block to the block chain
                //
                BlockHeader header = new BlockHeader(Parameters.GENESIS_BLOCK_BYTES, false);
                s1.setLong(1, getHashIndex(chainHead));
                s1.setBytes(2, chainHead.getBytes());
                s1.setBytes(3, Sha256Hash.ZERO_HASH.getBytes());
                s1.setInt(4, header.getVersion());
                s1.setLong(5, header.getBlockTime());
                s1.setLong(6, header.getTargetDifficulty());
                s1.setBytes(7, header.getMerkleRoot().getBytes());
                s1.setBytes(8, chainWork.toByteArray());
                s1.executeUpdate();
                //
                // Initialize the Settings table
                //
                s2.setString(1, schemaName);
                s2.setInt(2, schemaVersion);
                s2.executeUpdate();
                //
                // Database iniyialized
                //
                conn.commit();
                conn.setAutoCommit(true);
                log.info(String.format("SQL database initialized with schema version %d.%d",
                                       schemaVersion/100, schemaVersion%100));
            }
            //
            // All done - commit the updates
            //
            conn.commit();
            conn.setAutoCommit(true);
            log.info(String.format("Database initialized with schema version %d.%d",
                                   schemaVersion/100, schemaVersion%100));
        } catch (EOFException | SQLException | VerificationException exc) {
            log.error("Unable to initialize the database tables", exc);
            rollback();
            throw new WalletException("Unable to initialize the database tables");
        }
    }

    /**
     * Get the initial database settings
     *
     * @throws      WalletException     Unable to get the initial values
     */
    private void getSettings() throws WalletException {
        Connection conn = getConnection();
        ResultSet r;
        try (Statement s = conn.createStatement()) {
            //
            // Get the initial values from the Settings table
            //
            r = s.executeQuery("SELECT schema_version FROM Settings WHERE schema_name='" + schemaName + "'");
            if (!r.next())
                throw new WalletException("Incorrect database schema");
            int version = r.getInt(1);
            if (version > schemaVersion) {
                log.error(String.format("Schema version %d.%d is not supported", version/100, version%100));
                throw new WalletException("Schema version is not supported");
            }
            r.close();
            //
            // Update the database schema if necessary
            //
            switch (version) {
                case 100:
                    s.executeUpdate("ALTER TABLE Addresses ADD COLUMN IF NOT EXISTS type TINYINT");
                case 101:
                    s.executeUpdate("ALTER TABLE Sent ADD COLUMN IF NOT EXISTS address_type TINYINT");
                case 102:
                    s.executeUpdate("ALTER TABLE Settings ADD COLUMN IF NOT EXISTS witness_activated BOOLEAN");
                    s.executeUpdate("ALTER TABLE Settings ADD COLUMN IF NOT EXISTS previous_interval SMALLINT");
                    s.executeUpdate("ALTER TABLE Settings ADD COLUMN IF NOT EXISTS current_interval SMALLINT");
                case 103:
                    s.executeUpdate("ALTER TABLE Settings DROP COLUMN IF EXISTS witness_activated");
                    s.executeUpdate("ALTER TABLE Settings DROP COLUMN IF EXISTS previous_interval");
                    s.executeUpdate("ALTER TABLE Settings DROP COLUMN IF EXISTS current_interval");
                    //
                    // Insert new version updates before this comment
                    //
                    s.executeUpdate("UPDATE Settings SET schema_version=" + schemaVersion);
            }
            //
            // Get the current chain values from the chain head block
            //
            r = s.executeQuery("SELECT block_hash,block_height,chain_work FROM Headers "
                        + "WHERE block_height=(SELECT MAX(block_height) FROM Headers)");
            if (!r.next()) {
                log.error("SQL database is not initialized");
                throw new WalletException("SQL database is not initialized");
            }
            chainHead = new Sha256Hash(r.getBytes(1));
            chainHeight = r.getInt(2);
            chainWork = new BigInteger(r.getBytes(3));
            r.close();
            //
            // Initialization complete
            //
            log.info(String.format("Database opened with schema version %d.%d,  Chain height %d\n  Chain head %s",
                                   schemaVersion/100, schemaVersion%100, chainHeight, chainHead.toString()));
        } catch (SQLException exc) {
            log.error("Unable to get initial table settings", exc);
            throw new WalletException("Unable to get initial table settings");
        }
    }

    /**
     * Return the current and previous interval counters
     *
     * The interval counters are used to track soft fork activation as defined in BIP 9
     *
     * @return                          Current and previous interval counters
     * @throws      WalletException     Unable to get the interval counters
     */
    @Override
    public int[] getIntervalCounters() throws WalletException {
        Connection conn = getConnection();
        int[] result = new int[2];
        try (PreparedStatement s = conn.prepareStatement("SELECT current_interval,prev_interval FROM Settings")) {
            ResultSet r = s.executeQuery();
            if (r.next()) {
                result[0] = r.getShort(1);
                result[1] = r.getShort(2);
            }
        } catch (SQLException exc) {
            log.error("Unable to get interval counters", exc);
            throw new WalletException("Unable to get interval counters");
        }
        return result;
    }

    /**
     * Set the current and previous interval counters
     *
     * The interval counters are used to track soft fork activation as defined in BIP 9
     *
     * @param       currentInterval     Current interval counter
     * @param       prevInterval        Previous interval counter
     * @throws      WalletException     Unable to store the interval counters
     */
    @Override
    public void setIntervalCounters(int currentInterval, int prevInterval) throws WalletException {
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("UPDATE Settings SET current_interval=?,previous_interval=?")) {
            s.setShort(1, (short)currentInterval);
            s.setShort(2, (short)prevInterval);
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error("Unable to set interval counters", exc);
            throw new WalletException("Unable to set interval counters");
        }
    }

    /**
     * Returns the chain height of the latest block earlier than the requested time.
     *
     * @param       rescanTime          Block chain rescan time
     * @return                          Block height or 0 if no block meets the criteria
     * @throws      WalletException     Unable to get the chain height
     */
    @Override
    public int getRescanHeight(long rescanTime) throws WalletException {
        int height = 0;
        Connection conn = getConnection();
        ResultSet r;
        try (PreparedStatement s = conn.prepareStatement("SELECT block_height,timestamp FROM Headers "
                            + "WHERE timestamp<? ORDER BY timestamp DESC LIMIT 1")) {
            s.setLong(1, rescanTime);
            r = s.executeQuery();
            if (r.next())
                height = r.getInt(1);
        } catch (SQLException exc) {
            log.error("Unable to get rescan height", exc);
            throw new WalletException("Unable to get rescan height");
        }
        return height;
    }

    /**
     * Returns the block hash for the block at the requested height
     *
     * @param       blockHeight         Block height
     * @return                          Block Hash or null if block not found
     * @throws      WalletException     Unable to get block
     */
    @Override
    public Sha256Hash getBlockHash(int blockHeight) throws WalletException {
        Sha256Hash blockHash = null;
        Connection conn = getConnection();
        ResultSet r;
        try (PreparedStatement s = conn.prepareStatement("SELECT block_hash FROM Headers WHERE block_height=?")) {
            s.setInt(1, blockHeight);
            r = s.executeQuery();
            if (r.next())
                blockHash = new Sha256Hash(r.getBytes(1));
        } catch (SQLException exc) {
            log.error(String.format("Unable to get block at height %d", exc));
            throw new WalletException("Unable to get block");
        }
        return blockHash;
    }

    /**
     * Returns the chain list from the block following the start block up to the stop
     * block.  A maximum of 500 blocks will be returned.
     *
     * @param       startHeight         Start block height
     * @param       stopBlock           Stop block
     * @return                          Block hash list
     * @throws      WalletException     Unable to get blocks from database
     */
    @Override
    public List<Sha256Hash> getChainList(int startHeight, Sha256Hash stopBlock) throws WalletException {
        List<Sha256Hash> chainList = new ArrayList<>(500);
        Connection conn = getConnection();
        ResultSet r;
        try (PreparedStatement s = conn.prepareStatement("SELECT block_hash,block_height FROM Headers "
                            + "WHERE block_height BETWEEN ? AND ? ORDER BY block_height ASC")) {
            s.setInt(1, startHeight+1);
            s.setInt(2, startHeight+500);
            r = s.executeQuery();
            while (r.next()) {
                Sha256Hash blockHash = new Sha256Hash(r.getBytes(1));
                chainList.add(blockHash);
                if (blockHash.equals(stopBlock))
                    break;
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to get chain list starting at height %d", startHeight), exc);
            throw new WalletException("Unable to get chain list");
        }
        return chainList;
    }

    /**
     * Get the address type
     *
     * @param       address             Address
     * @throws      WalletException     Unsupported address type
     */
    private int getAddressType(Address address) throws WalletException {
        int addressType;
        switch (address.getType()) {
            case P2PKH:
                addressType = 0;
                break;
            case P2SH:
                addressType = 1;
                break;
            default:
                throw new WalletException("Unsupported address type " + address.getType());
        }
        return addressType;
    }

    /**
     * Stores an address
     *
     * @param       address             Address
     * @throws      WalletException     Unable to store the address
     */
    @Override
    public void storeAddress(Address address) throws WalletException {
        int addressType = getAddressType(address);
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO Addresses "
                + "(type,address,label) VALUES(?,?,?)")) {
            s.setByte(1, (byte)addressType);
            s.setBytes(2, address.getHash());
            if (address.getLabel().isEmpty())
                s.setNull(3, Types.VARCHAR);
            else
                s.setString(3, address.getLabel());
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error("Unable to store address", exc);
            throw new WalletException("Unable to store address");
        }
    }

    /**
     * Sets the address label
     *
     * @param       address             Address
     * @throws      WalletException     Unable to update label
     */
    @Override
    public void setAddressLabel(Address address) throws WalletException {
        int addressType = getAddressType(address);
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("UPDATE Addresses SET label=? "
                + "WHERE type=? AND address=?")) {
            if (address.getLabel().isEmpty())
                s.setNull(1, Types.VARCHAR);
            else
                s.setString(1, address.getLabel());
            s.setByte(2, (byte)addressType);
            s.setBytes(3, address.getHash());
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error("Unable to update address label", exc);
            throw new WalletException("Unable to update address label");
        }
    }

    /**
     * Deletes an address
     *
     * @param       address             Address
     * @throws      WalletException     Unable to delete address
     */
    @Override
    public void deleteAddress(Address address) throws WalletException {
        int addressType = getAddressType(address);
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("DELETE FROM Addresses "
                + "WHERE type=? AND address=?")) {
            s.setByte(1, (byte)addressType);
            s.setBytes(2, address.getHash());
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error("Unable to delete address", exc);
            throw new WalletException("Unable to delete address");
        }
    }

    /**
     * Returns a list of all addresses sorted by the label
     *
     * @return                          List of addresses stored in the database
     * @throws      WalletException     Unable to get address list
     */
    @Override
    public List<Address> getAddressList() throws WalletException {
        List<Address> addressList = new ArrayList<>();
        Connection conn = getConnection();
        ResultSet r;
        try (Statement s = conn.createStatement()) {
            r = s.executeQuery("SELECT type,address,label FROM Addresses ORDER BY label ASC NULLS FIRST");
            while (r.next()) {
                Address.AddressType type = (r.getByte(1)==1 ? Address.AddressType.P2SH : Address.AddressType.P2PKH);
                byte[] hash = r.getBytes(2);
                String label = r.getString(3);
                addressList.add(new Address(type, hash, label!=null?label:""));
            }
        } catch (SQLException exc) {
            log.error("Unable to get address list", exc);
            throw new WalletException("Unable to get address list");
        }
        return addressList;
    }

    /**
     * Stores a key
     *
     * @param       key                 Public/private key pair
     * @throws      WalletException     Unable to store the key
     */
    @Override
    public void storeKey(ECKey key) throws WalletException {
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO Keys "
                            + "(public_key,private_key,timestamp,is_change,label) VALUES(?,?,?,?,?)")) {
            EncryptedPrivateKey encPrivKey = new EncryptedPrivateKey(key.getPrivKey(), Parameters.passPhrase);
            s.setBytes(1, key.getPubKey());
            s.setBytes(2, encPrivKey.getBytes());
            s.setLong(3, key.getCreationTime());
            s.setBoolean(4, key.isChange());
            if (key.getLabel().isEmpty())
                s.setNull(5, Types.VARCHAR);
            else
                s.setString(5, key.getLabel());
            s.executeUpdate();
        } catch (ECException | SQLException exc) {
            log.error("Unable to store key", exc);
            throw new WalletException("Unable to store key");
        }
    }

    /**
     * Sets the key label
     *
     * @param       key                 Public/private key pair
     * @throws      WalletException     Unable to update the label
     */
    @Override
    public void setKeyLabel(ECKey key) throws WalletException {
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("UPDATE Keys SET label=? WHERE public_key=?")) {
            if (key.getLabel().isEmpty())
                s.setNull(1, Types.VARCHAR);
            else
                s.setString(1, key.getLabel());
            s.setBytes(2, key.getPubKey());
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error("Unable to update key label", exc);
            throw new WalletException("Unable to update key label");
        }
    }

    /**
     * Returns a list of all keys sorted by the label
     *
     * @return                          List of keys stored in the database
     * @throws      KeyException        Private key does not match public key
     * @throws      WalletException     Unable to get address list
     */
    @Override
    public List<ECKey> getKeyList() throws KeyException, WalletException {
        List<ECKey> keyList = new ArrayList<>();
        Connection conn = getConnection();
        ResultSet r;
        try (Statement s = conn.createStatement()) {
            r = s.executeQuery("SELECT public_key,private_key,timestamp,is_change,label FROM Keys "
                            + "ORDER BY label ASC NULLS FIRST");
            while (r.next()) {
                byte[] pubKey = r.getBytes(1);
                EncryptedPrivateKey encPrivKey = new EncryptedPrivateKey(r.getBytes(2));
                ECKey key = new ECKey(encPrivKey.getPrivKey(Parameters.passPhrase), (pubKey.length==33));
                if (!Arrays.equals(key.getPubKey(), pubKey))
                    throw new KeyException("Private key does not match public key");
                key.setCreationTime(r.getLong(3));
                key.setChange(r.getBoolean(4));
                String label = r.getString(5);
                key.setLabel(label!=null?label:"");
                keyList.add(key);
            }
        } catch (EOFException | ECException | SQLException exc) {
            log.error("Unable to get key list", exc);
            throw new WalletException("Unable to get key list");
        }
        return keyList;
    }

    /**
     * Checks if this is a new block
     *
     * @param       blockHash           Block hash
     * @return                          TRUE if this is a new block
     * @throws      WalletException     Unable to check block status
     */
    @Override
    public boolean isNewBlock(Sha256Hash blockHash) throws WalletException {
        boolean isNew = true;
        Connection conn = getConnection();
        ResultSet r;
        try (PreparedStatement s = conn.prepareStatement("SELECT 1 FROM Headers "
                            + "WHERE block_hash_index=? AND block_hash=?")) {
            s.setLong(1, getHashIndex(blockHash));
            s.setBytes(2, blockHash.getBytes());
            r = s.executeQuery();
            isNew = !r.next();
        } catch (SQLException exc) {
            log.error(String.format("Unable to check block status\n  Block %s", blockHash), exc);
            throw new WalletException("Unable to check block status");
        }
        return isNew;
    }

    /**
     * Stores a block header
     *
     * @param       storedHeader        Block header
     * @throws      WalletException     Unable to store the block header
     */
    @Override
    public void storeHeader(StoredHeader storedHeader) throws WalletException {
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO Headers "
                            + "(block_hash_index,block_hash,prev_hash_index,prev_hash,version,timestamp,"
                            + "target_difficulty,merkle_root,block_height,chain_work,matches) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setLong(1, getHashIndex(storedHeader.getHash()));
            s.setBytes(2, storedHeader.getHash().getBytes());
            s.setLong(3, getHashIndex(storedHeader.getPrevHash()));
            s.setBytes(4, storedHeader.getPrevHash().getBytes());
            s.setInt(5, storedHeader.getVersion());
            s.setLong(6, storedHeader.getBlockTime());
            s.setLong(7, storedHeader.getTargetDifficulty());
            s.setBytes(8, storedHeader.getMerkleRoot().getBytes());
            s.setInt(9, storedHeader.isOnChain() ? storedHeader.getBlockHeight() : -1);
            s.setBytes(10, storedHeader.getChainWork().toByteArray());
            if (storedHeader.getMatches()==null || storedHeader.getMatches().isEmpty())
                s.setNull(11, Types.BINARY);
            else
                s.setBytes(11, getMatches(storedHeader.getMatches()));
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error(String.format("Unable to store block header\n  Block %s", storedHeader.getHash()), exc);
            throw new WalletException("Unable to store block header");
        }
    }

    /**
     * Updates the matched transactions for a block
     *
     * @param       header              Block Header
     * @throws      WalletException     Unable to update the database
     */
    @Override
    public void updateMatches(BlockHeader header) throws WalletException {
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("UPDATE Headers SET matches=? "
                            + "WHERE block_hash_index=? AND block_hash=?")) {
            if (header.getMatches()==null || header.getMatches().isEmpty())
                s.setNull(1, Types.BINARY);
            else
                s.setBytes(1, getMatches(header.getMatches()));
            s.setLong(2, getHashIndex(header.getHash()));
            s.setBytes(3, header.getHash().getBytes());
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error(String.format("Unable to update matched transactions\n  Block %s", header.getHash()), exc);
            throw new WalletException("Unable to update matched transactions");
        }
    }

    /**
     * Returns a block header stored in the database
     *
     * @param       blockHash           Block hash
     * @return                          Block header or null if the block is not found
     * @throws      WalletException     Unable to retrieve the block header
     */
    @Override
    public StoredHeader getHeader(Sha256Hash blockHash) throws WalletException {
        StoredHeader header = null;
        Connection conn = getConnection();
        ResultSet r;
        try (PreparedStatement s = conn.prepareStatement("SELECT prev_hash,version,timestamp,target_difficulty,"
                            + "merkle_root,block_height,chain_work,matches FROM Headers "
                            + "WHERE block_hash_index=? AND block_hash=?")) {
            s.setLong(1, getHashIndex(blockHash));
            s.setBytes(2, blockHash.getBytes());
            r = s.executeQuery();
            if (r.next()) {
                Sha256Hash prevHash = new Sha256Hash(r.getBytes(1));
                int version = r.getInt(2);
                long timestamp = r.getLong(3);
                long targetDifficulty = r.getLong(4);
                Sha256Hash merkleRoot = new Sha256Hash(r.getBytes(5));
                int blockHeight = r.getInt(6);
                BigInteger blockWork = new BigInteger(r.getBytes(7));
                List<Sha256Hash> matches = getMatches(r.getBytes(8));
                header = new StoredHeader(version, blockHash, prevHash, timestamp, targetDifficulty,
                                          merkleRoot, blockHeight>=0, blockHeight, blockWork, matches);
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to get block header\n  Block %s", blockHash), exc);
            throw new WalletException("Unable to get block header");
        }
        return header;
    }

    /**
     * * Return the block versions for the interval containing the supplied block
     *
     * An interval consists of 2106 blocks
     *
     * @param       height              Block height
     * @return                          Version list from the interval start to the supplied height
     * @throws      WalletException     Unable to retrieve the block versions
     */
    @Override
    public List<Integer> getBlockVersions(int height) throws WalletException {
        int baseHeight = (height / 2106) * 2106;
        List<Integer> versions = new ArrayList<>(height - baseHeight + 1);
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("SELECT version FROM Headers "
                + "WHERE block_height>=? AND block_height<=? ORDER BY block_height ASC")) {
            s.setInt(1, baseHeight);
            s.setInt(2, height);
            ResultSet r = s.executeQuery();
            while (r.next()) {
                versions.add(r.getInt(1));
            }
        } catch (SQLException exc) {
            log.error("Unable to get block versions", exc);
            throw new WalletException("Unable to get block versions");
        }
        return versions;
    }

    /**
     * Returns the block header for the child of the specified block
     *
     * @param       parentHash          Parent block hash
     * @return                          Child block header or null if no child is found
     * @throws      WalletException     Unable to retrieve the child block header
     */
    @Override
    public StoredHeader getChildHeader(Sha256Hash parentHash) throws WalletException {
        StoredHeader header = null;
        Connection conn = getConnection();
        ResultSet r;
        try (PreparedStatement s = conn.prepareStatement("SELECT block_hash,version,timestamp,target_difficulty,"
                            + "merkle_root,block_height,chain_work,matches FROM Headers "
                            + "WHERE prev_hash_index=? AND prev_hash=?")) {
            s.setLong(1, getHashIndex(parentHash));
            s.setBytes(2, parentHash.getBytes());
            r = s.executeQuery();
            if (r.next()) {
                Sha256Hash blockHash = new Sha256Hash(r.getBytes(1));
                int version = r.getInt(2);
                long timestamp = r.getLong(3);
                long targetDifficulty = r.getLong(4);
                Sha256Hash merkleRoot = new Sha256Hash(r.getBytes(5));
                int blockHeight = r.getInt(6);
                BigInteger blockWork = new BigInteger(r.getBytes(7));
                List<Sha256Hash> matches = getMatches(r.getBytes(8));
                header = new StoredHeader(version, blockHash, parentHash, timestamp, targetDifficulty,
                                          merkleRoot, blockHeight>=0, blockHeight, blockWork, matches);
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to get child header\n  Block %s", parentHash), exc);
            throw new WalletException("Unable to get child header");
        }
        return header;
    }

    /**
     * Checks if this is a new transaction
     *
     * @param       txHash              Transaction hash
     * @return                          TRUE if this is a new transaction
     * @throws      WalletException     Unable to check transaction status
     */
    @Override
    public boolean isNewTransaction(Sha256Hash txHash) throws WalletException {
        boolean isNew = true;
        Connection conn = getConnection();
        ResultSet r;
        try (PreparedStatement s = conn.prepareStatement("SELECT 1 FROM Received "
                            + "WHERE tx_hash_index=? AND tx_hash=? UNION SELECT 2 FROM Sent "
                            + "WHERE tx_hash_index=? AND tx_hash=?")) {
            long hashIndex = getHashIndex(txHash);
            s.setLong(1, hashIndex);
            s.setBytes(2, txHash.getBytes());
            s.setLong(3, hashIndex);
            s.setBytes(4, txHash.getBytes());
            r = s.executeQuery();
            isNew = !r.next();
        } catch (SQLException exc) {
            log.error(String.format("Unable to check for new transaction\n  Tx %s", txHash), exc);
            throw new WalletException("Unable to check for new transaction");
        }
        return isNew;
    }

    /**
     * Store a receive transaction
     *
     * @param       receiveTx           Receive transaction
     * @throws      WalletException     Unable to store the transaction
     */
    @Override
    public void storeReceiveTx(ReceiveTransaction receiveTx) throws WalletException {
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO Received "
                            + "(tx_hash_index,tx_hash,tx_index,norm_hash,timestamp,block_hash,address,"
                            + "value,script_bytes,is_spent,is_change,in_safe,is_coinbase,is_deleted) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,false,?,false,?,false)")) {
            s.setLong(1, getHashIndex(receiveTx.getTxHash()));
            s.setBytes(2, receiveTx.getTxHash().getBytes());
            s.setShort(3, (short)receiveTx.getTxIndex());
            s.setBytes(4, receiveTx.getNormalizedID().getBytes());
            s.setLong(5, receiveTx.getTxTime());
            if (receiveTx.getBlockHash() == null)
                s.setNull(6, Types.BINARY);
            else
                s.setBytes(6, receiveTx.getBlockHash().getBytes());
            s.setBytes(7, receiveTx.getAddress().getHash());
            s.setLong(8, receiveTx.getValue().longValue());
            s.setBytes(9, receiveTx.getScriptBytes());
            s.setBoolean(10, receiveTx.isChange());
            s.setBoolean(11, receiveTx.isCoinBase());
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error(String.format("Unable to store receive transaction output\n  Tx %s[%d]",
                                    receiveTx.getTxHash(), receiveTx.getTxIndex()), exc);
            throw new WalletException("Unable to store receive transaction output");
        }
    }

    /**
     * Updates the spent status for a receive transaction
     *
     * @param       txHash              Transaction hash
     * @param       txIndex             Transaction output index
     * @param       isSpent             TRUE if the transaction output has been spent
     * @throws      WalletException     Unable to update transaction status
     */
    @Override
    public void setTxSpent(Sha256Hash txHash, int txIndex, boolean isSpent) throws WalletException {
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("UPDATE Received SET is_spent=? "
                            + "WHERE tx_hash_index=? AND tx_hash=? and tx_index=?")) {
            s.setBoolean(1, isSpent);
            s.setLong(2, getHashIndex(txHash));
            s.setBytes(3, txHash.getBytes());
            s.setShort(4, (short)txIndex);
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error(String.format("Unable to update receive transaction output\n  Tx %s[%d]",
                                    txHash, txIndex), exc);
            throw new WalletException("Unable to update receive transaction outputs");
        }
    }

    /**
     * Updates the safe status for a receive transaction
     *
     * @param       txHash              Transaction hash
     * @param       txIndex             Transaction output index
     * @param       inSafe              TRUE if the transaction output is in the safe
     * @throws      WalletException     Unable to update transaction status
     */
    @Override
    public void setTxSafe(Sha256Hash txHash, int txIndex, boolean inSafe) throws WalletException {
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("UPDATE Received SET in_safe=? "
                            + "WHERE tx_hash_index=? AND tx_hash=? and tx_index=?")) {
            s.setBoolean(1, inSafe);
            s.setLong(2, getHashIndex(txHash));
            s.setBytes(3, txHash.getBytes());
            s.setShort(4, (short)txIndex);
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error(String.format("Unable to update receive transaction output\n  Tx %s[%d]",
                                    txHash, txIndex), exc);
            throw new WalletException("Unable to update receive transaction outputs");
        }
    }

    /**
     * Updates the delete status for a receive transaction
     *
     * @param       txHash              Transaction hash
     * @param       txIndex             Transaction output index
     * @param       isDeleted           TRUE if the transaction output is deleted
     * @throws      WalletException     Unable to update transaction status
     */
    @Override
    public void setReceiveTxDelete(Sha256Hash txHash, int txIndex, boolean isDeleted) throws WalletException {
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("UPDATE Received SET is_deleted=? "
                            + "WHERE tx_hash_index=? AND tx_hash=? and tx_index=?")) {
            s.setBoolean(1, isDeleted);
            s.setLong(2, getHashIndex(txHash));
            s.setBytes(3, txHash.getBytes());
            s.setShort(4, (short)txIndex);
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error(String.format("Unable to update receive transaction output\n  Tx %s[%d]",
                                    txHash, txIndex), exc);
            throw new WalletException("Unable to update receive transaction outputs");
        }
    }

    /**
     * Returns a list of all receive transactions that have not been deleted.  If we have multiple
     * transactions with the same normalized ID, we will return the one that has been confirmed.
     * If none of them are confirmed, we will return the first one we encounter.
     *
     * @return                          List of receive transactions
     * @throws      WalletException     Unable to get transaction list
     */
    @Override
    public List<ReceiveTransaction> getReceiveTxList() throws WalletException {
        List<ReceiveTransaction> txList = new LinkedList<>();
        Map<TransactionID, ReceiveTransaction> txMap = new HashMap<>();
        Connection conn = getConnection();
        ResultSet r;
        try (PreparedStatement s = conn.prepareStatement("SELECT tx_hash,tx_index,norm_hash,timestamp,"
                            + "block_hash,address,value,script_bytes,is_spent,is_change,in_safe,is_coinbase "
                            + "FROM Received WHERE is_deleted=false")) {
            r = s.executeQuery();
            while (r.next()) {
                Sha256Hash txHash = new Sha256Hash(r.getBytes(1));
                int txIndex = r.getShort(2);
                Sha256Hash normID = new Sha256Hash(r.getBytes(3));
                long txTime = r.getLong(4);
                byte[] bytes = r.getBytes(5);
                Sha256Hash blockHash = (bytes!=null ? new Sha256Hash(bytes) : null);
                Address address = new Address(r.getBytes(6));   // Receive transactions always use P2PKH address
                BigInteger value = BigInteger.valueOf(r.getLong(7));
                byte[] scriptBytes = r.getBytes(8);
                boolean isSpent = r.getBoolean(9);
                boolean isChange = r.getBoolean(10);
                boolean inSafe = r.getBoolean(11);
                boolean isCoinbase = r.getBoolean(12);
                TransactionID txID = new TransactionID(txHash, txIndex);
                ReceiveTransaction tx = new ReceiveTransaction(normID, txHash, txIndex, txTime, blockHash,
                                                               address, value, scriptBytes, isSpent,
                                                               isChange, isCoinbase, inSafe);
                ReceiveTransaction prevTx = txMap.get(txID);
                if (blockHash != null) {
                    if (prevTx != null)
                        txList.remove(prevTx);
                    txList.add(tx);
                    txMap.put(txID, tx);
                } else if (prevTx == null) {
                    txList.add(tx);
                    txMap.put(txID, tx);
                }
            }
        } catch (SQLException exc) {
            log.error("Unable to get receive transaction list", exc);
            throw new WalletException("Unable to get receive transaction list");
        }
        return txList;
    }

    /**
     * Store a send transaction
     *
     * @param       sendTx              Send transaction
     * @throws      WalletException     Unable to store the transaction
     */
    @Override
    public void storeSendTx(SendTransaction sendTx) throws WalletException {
        int addressType = getAddressType(sendTx.getAddress());
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO Sent "
                    + "(tx_hash_index,tx_hash,norm_hash,timestamp,block_hash,"
                    + "address_type,address,value,fee,is_deleted,tx_data) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,false,?)")) {
            s.setLong(1, getHashIndex(sendTx.getTxHash()));
            s.setBytes(2, sendTx.getTxHash().getBytes());
            s.setBytes(3, sendTx.getNormalizedID().getBytes());
            s.setLong(4, sendTx.getTxTime());
            if (sendTx.getBlockHash() == null)
                s.setNull(5, Types.BINARY);
            else
                s.setBytes(5, sendTx.getBlockHash().getBytes());
            s.setByte(6, (byte)addressType);
            s.setBytes(7, sendTx.getAddress().getHash());
            s.setLong(8, sendTx.getValue().longValue());
            s.setLong(9, sendTx.getFee().longValue());
            s.setBytes(10, sendTx.getTxData());
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error(String.format("Unable to store send transaction\n  Tx %s", sendTx.getTxHash()), exc);
            throw new WalletException("Unable to store send transaction");
        }
    }

    /**
     * Updates the delete status for a send transaction
     *
     * @param       txHash              Transaction hash
     * @param       isDeleted           TRUE if the transaction is deleted
     * @throws      WalletException     Unable to update transaction status
     */
    @Override
    public void setSendTxDelete(Sha256Hash txHash, boolean isDeleted) throws WalletException {
        Connection conn = getConnection();
        try (PreparedStatement s = conn.prepareStatement("UPDATE Sent SET is_deleted=? "
                            + "WHERE tx_hash_index=? AND tx_hash=?")) {
            s.setBoolean(1, isDeleted);
            s.setLong(2, getHashIndex(txHash));
            s.setBytes(3, txHash.getBytes());
            s.executeUpdate();
        } catch (SQLException exc) {
            log.error(String.format("Unable to update send transaction\n  Tx %s", txHash), exc);
            throw new WalletException("Unable to update send transaction");
        }
    }

    /**
     * Returns the requested send transaction
     *
     * @param       txHash              Send transaction hash
     * @return                          Transaction or null if not found or is deleted
     * @throws      WalletException     Unable to get the transaction from the database
     */
    @Override
    public SendTransaction getSendTx(Sha256Hash txHash) throws WalletException {
        SendTransaction tx = null;
        Connection conn = getConnection();
        ResultSet r;
        try (PreparedStatement s = conn.prepareStatement("SELECT norm_hash,timestamp,block_hash,"
                + "address_type,address,value,fee,tx_data "
                + "FROM Sent WHERE tx_hash_index=? AND tx_hash=? AND is_deleted=false")) {
            s.setLong(1, getHashIndex(txHash));
            s.setBytes(2, txHash.getBytes());
            r = s.executeQuery();
            if (r.next()) {
                Sha256Hash normID = new Sha256Hash(r.getBytes(1));
                long txTime = r.getLong(2);
                byte[] bytes = r.getBytes(3);
                Sha256Hash blockHash = (bytes!=null ? new Sha256Hash(bytes) : null);
                Address.AddressType type = (r.getByte(4)==1 ? Address.AddressType.P2SH : Address.AddressType.P2PKH);
                Address address = new Address(type, r.getBytes(5));
                BigInteger value = BigInteger.valueOf(r.getLong(6));
                BigInteger fee = BigInteger.valueOf(r.getLong(7));
                byte[] txData = r.getBytes(8);
                tx = new SendTransaction(normID, txHash, txTime, blockHash, address, value, fee, txData);
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to get send transaction\n  Tx %s", txHash), exc);
            throw new WalletException("Unable to get send transaction");
        }
        return tx;
    }

    /**
     * Returns a list of all send transactions that have not been deleted.  If we have multiple
     * transactions with the same normalized ID, we will return the one that has been confirmed.
     * If none of them are confirmed, we will return the first one we encounter.
     *
     * @return                          List of send transactions
     * @throws      WalletException     Unable to get transaction list
     */
    @Override
    public List<SendTransaction> getSendTxList() throws WalletException {
        List<SendTransaction> txList = new LinkedList<>();
        Map<Sha256Hash, SendTransaction> txMap = new HashMap<>();
        Connection conn = getConnection();
        ResultSet r;
        try (PreparedStatement s = conn.prepareStatement("SELECT tx_hash,norm_hash,timestamp,"
                + "block_hash,address_type,address,value,fee,tx_data "
                + "FROM Sent WHERE is_deleted=false")) {
            r = s.executeQuery();
            while (r.next()) {
                Sha256Hash txHash = new Sha256Hash(r.getBytes(1));
                Sha256Hash normID = new Sha256Hash(r.getBytes(2));
                long txTime = r.getLong(3);
                byte[] bytes = r.getBytes(4);
                Sha256Hash blockHash = (bytes!=null ? new Sha256Hash(bytes) : null);
                Address.AddressType type = (r.getByte(5)==1 ? Address.AddressType.P2SH : Address.AddressType.P2PKH);
                Address address = new Address(type, r.getBytes(6));
                BigInteger value = BigInteger.valueOf(r.getLong(7));
                BigInteger fee = BigInteger.valueOf(r.getLong(8));
                byte[] txData = r.getBytes(9);
                SendTransaction tx = new SendTransaction(normID, txHash, txTime, blockHash, address,
                                                         value, fee, txData);
                SendTransaction prevTx = txMap.get(normID);
                if (blockHash != null) {
                    if (prevTx != null)
                        txList.remove(prevTx);
                    txList.add(tx);
                    txMap.put(normID, tx);
                } else if (prevTx == null) {
                    txList.add(tx);
                    txMap.put(normID, tx);
                }
            }
        } catch (SQLException exc) {
            log.error("Unable to get send transaction list", exc);
            throw new WalletException("Unable to get send transaction list");
        }
        return txList;
    }

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
    @Override
    public int getTxDepth(Sha256Hash txHash) throws WalletException {
        int txDepth = 0;
        Connection conn = getConnection();
        ResultSet r;
        try (PreparedStatement s1 = conn.prepareStatement("SELECT block_hash FROM Received "
                            + "WHERE tx_hash_index=? AND tx_hash=? UNION SELECT block_hash FROM Sent "
                            + "WHERE tx_hash_index=? AND tx_hash=?");
                PreparedStatement s2 = conn.prepareStatement("SELECT block_height FROM Headers "
                            + "WHERE block_hash_index=? AND block_hash=?")) {
            long hashIndex = getHashIndex(txHash);
            s1.setLong(1, hashIndex);
            s1.setBytes(2, txHash.getBytes());
            s1.setLong(3, hashIndex);
            s1.setBytes(4, txHash.getBytes());
            r = s1.executeQuery();
            if (r.next()) {
                byte[] bytes = r.getBytes(1);
                if (bytes != null) {
                    r.close();
                    Sha256Hash blockHash = new Sha256Hash(bytes);
                    s2.setLong(1, getHashIndex(blockHash));
                    s2.setBytes(2, blockHash.getBytes());
                    r = s2.executeQuery();
                    if (r.next())
                        txDepth = chainHeight - r.getInt(1) + 1;
                }
            }
        } catch (SQLException exc) {
            log.error(String.format("Unable to get transaction depth\n  Tx %s", txHash), exc);
            throw new WalletException("Unable to get transaction depth");
        }
        return txDepth;
    }

    /**
     * Deletes all wallet transactions.
     *
     * @throws      WalletException         Unable to delete transactions
     */
    @Override
    public void deleteTransactions() throws WalletException {
        Connection conn = getConnection();
        try (Statement s1 = conn.createStatement()) {
            s1.execute("TRUNCATE TABLE Received");
            s1.execute("TRUNCATE TABLE Sent");
        } catch (SQLException exc) {
            log.error("Unable to delete wallet transactions", exc);
            throw new WalletException("Unable to delete wallet transactions");
        }
    }

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
    @Override
    public List<StoredHeader> getJunction(Sha256Hash chainHash) throws BlockNotFoundException, WalletException {
        List<StoredHeader> chainList = new LinkedList<>();
        boolean onChain = false;
        Sha256Hash blockHash = chainHash;
        synchronized (lock) {
            //
            // Starting with the supplied block, follow the previous hash values until
            // we reach a block which is on the block chain.  This block is the junction
            // block.
            //
            while (!onChain) {
                StoredHeader header = getHeader(blockHash);
                if (header == null) {
                    log.debug(String.format("Chain block is not available\n  Block %s", blockHash));
                    throw new BlockNotFoundException("Unable to resolve block chain", blockHash);
                }
                chainList.add(0, header);
                blockHash = header.getPrevHash();
                onChain = header.isOnChain();
            }
        }
        return chainList;
    }

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
    @Override
    public void setChainHead(List<StoredHeader> chainList) throws WalletException, VerificationException {
        //
        // See if we have reached a checkpoint.  If we have, the new block at that height
        // must match the checkpoint block.
        //
        for (StoredHeader header : chainList) {
            Sha256Hash checkHash = checkpoints.get(Integer.valueOf(header.getBlockHeight()));
            if (checkHash != null) {
                if (checkHash.equals(header.getHash())) {
                    log.info(String.format("New chain head at height %d matches checkpoint",
                                           header.getBlockHeight()));
                } else {
                    log.error(String.format("New chain head at height %d does not match checkpoint",
                                            header.getBlockHeight()));
                    throw new VerificationException("Checkpoint verification failed",
                                                    RejectMessage.REJECT_CHECKPOINT, header.getHash());
                }
            }
        }
        StoredHeader chainHeader = chainList.get(chainList.size()-1);
        Connection conn = getConnection();
        ResultSet r;
        //
        // Make the new block the chain head
        //
        synchronized (lock) {
            StoredHeader header;
            Sha256Hash blockHash;
            Sha256Hash prevHash;
            List<Sha256Hash> txList;
            try (PreparedStatement s1 = conn.prepareStatement("SELECT prev_hash,matches FROM Headers "
                            + "WHERE block_hash_index=? AND block_hash=? FOR UPDATE");
                    PreparedStatement s2 = conn.prepareStatement("UPDATE Received SET block_hash=? "
                            + "WHERE tx_hash_index=? AND tx_hash=?");
                    PreparedStatement s3 = conn.prepareStatement("UPDATE Sent SET block_hash=? "
                            + "WHERE tx_hash_index=? AND tx_hash=?");
                    PreparedStatement s4 = conn.prepareStatement("UPDATE Headers SET block_height=-1 "
                            + "WHERE block_hash_index=? AND block_hash=?");
                    PreparedStatement s5 = conn.prepareStatement("UPDATE Headers SET block_height=?,chain_work=? "
                            + "WHERE block_hash_index=? AND block_hash=?")) {
                conn.setAutoCommit(false);
                //
                // The ideal case is where the new block links to the current chain head.
                // If this is not the case, we need to remove all blocks from the block
                // chain following the junction block.
                //
                if (!chainHead.equals(chainHeader.getPrevHash())) {
                    Sha256Hash junctionHash = chainList.get(0).getHash();
                    blockHash = chainHead;
                    //
                    // Process each block starting at the current chain head and working backwards
                    // until we reach the junction block
                    //
                    while(!blockHash.equals(junctionHash)) {
                        //
                        // Get the block from the Headers database
                        //
                        s1.setLong(1, getHashIndex(blockHash));
                        s1.setBytes(2, blockHash.getBytes());
                        r = s1.executeQuery();
                        if (!r.next()) {
                            log.error(String.format("Chain block not found\n  Block %s", blockHash));
                            throw new WalletException("Chain block not found", blockHash);
                        }
                        prevHash = new Sha256Hash(r.getBytes(1));
                        byte[] bytes = r.getBytes(2);
                        r.close();
                        //
                        // Update the matched transactions to indicate they are no longer confirmed
                        //
                        if (bytes != null) {
                            txList = getMatches(bytes);
                            for (Sha256Hash txHash : txList) {
                                s2.setNull(1, Types.BINARY);
                                s2.setLong(2, getHashIndex(txHash));
                                s2.setBytes(3, txHash.getBytes());
                                s2.executeUpdate();
                                s3.setNull(1, Types.BINARY);
                                s3.setLong(2, getHashIndex(txHash));
                                s3.setBytes(3, txHash.getBytes());
                                s3.executeUpdate();
                            }
                        }
                        //
                        // Remove the block from the chain
                        //
                        s4.setLong(1, getHashIndex(blockHash));
                        s4.setBytes(2, blockHash.getBytes());
                        s4.executeUpdate();
                        log.info(String.format("Block removed from block chain\n  Block %s", blockHash));
                        //
                        // Advance to the block before this block
                        //
                        blockHash = prevHash;
                    }
                }
                //
                // Now add the new blocks to the block chain starting with the
                // block following the junction block
                //
                for (int i=1; i<chainList.size(); i++) {
                    header = chainList.get(i);
                    blockHash = header.getHash();
                    int blockHeight = header.getBlockHeight();
                    txList = header.getMatches();
                    //
                    // Update the matched transactions for this block to indicate
                    // they are now confirmed
                    //
                    if (txList != null) {
                        for (Sha256Hash txHash : txList) {
                            s2.setBytes(1, blockHash.getBytes());
                            s2.setLong(2, getHashIndex(txHash));
                            s2.setBytes(3, txHash.getBytes());
                            s2.executeUpdate();
                            s3.setBytes(1, blockHash.getBytes());
                            s3.setLong(2, getHashIndex(txHash));
                            s3.setBytes(3, txHash.getBytes());
                            s3.executeUpdate();
                        }
                    }
                    //
                    // Update the block status
                    //
                    s5.setInt(1, blockHeight);
                    s5.setBytes(2, header.getChainWork().toByteArray());
                    s5.setLong(3, getHashIndex(blockHash));
                    s5.setBytes(4, blockHash.getBytes());
                    s5.executeUpdate();
                    log.info(String.format("Block added to block chain at height %d, Difficulty %d\n  Block %s",
                                           blockHeight, header.getChainWork(), blockHash));
                }
                //
                // Commit the changes
                //
                conn.commit();
                conn.setAutoCommit(true);
                chainHead = chainHeader.getHash();
                chainHeight = chainHeader.getBlockHeight();
                chainWork = chainHeader.getChainWork();
            } catch (SQLException exc) {
                log.error("Unable to update block chain", exc);
                rollback();
                throw new WalletException("Unable to update block chain");
            }
        }
    }

    /**
     * TransactionID consists of the transaction hash plus the transaction output index
     */
    private class TransactionID {

        /** Transaction hash */
        private final Sha256Hash txHash;

        /** Transaction output index */
        private final int txIndex;

        /**
         * Creates the transaction ID
         *
         * @param       txHash          Transaction hash
         * @param       txIndex         Transaction output index
         */
        public TransactionID(Sha256Hash txHash, int txIndex) {
            this.txHash = txHash;
            this.txIndex = txIndex;
        }

        /**
         * Returns the transaction hash
         *
         * @return                  Transaction hash
         */
        public Sha256Hash getTxHash() {
            return txHash;
        }

        /**
         * Returns the transaction output index
         *
         * @return                  Transaction output index
         */
        public int getTxIndex() {
            return txIndex;
        }

        /**
         * Compares two objects
         *
         * @param       obj         Object to compare
         * @return                  TRUE if the objects are equal
         */
        @Override
        public boolean equals(Object obj) {
            return (obj!=null && (obj instanceof TransactionID) &&
                        txHash.equals(((TransactionID)obj).txHash) &&
                        txIndex==((TransactionID)obj).txIndex);
        }

        /**
         * Returns the hash code
         *
         * @return                  Hash code
         */
        @Override
        public int hashCode() {
            return txHash.hashCode();
        }
    }
}
