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
import org.ScripterRon.BitcoinCore.ECKey;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;

/**
 * TransactionPanel displays a table containing all of the wallet transactions
 */
public class TransactionPanel extends JPanel implements ActionListener {

    /** Transaction table column classes */
    private static final Class<?>[] columnClasses = {
        Date.class, String.class, String.class, String.class, BigInteger.class, BigInteger.class,
        String.class, String.class};

    /** Transaction table column names */
    private static final String[] columnNames = {
        "Date", "Transaction ID", "Type", "Name/Address", "Amount", "Fee",
        "Location", "Status"};

    /** Transaction table column types */
    private static final int[] columnTypes = {
        SizedTable.DATE, SizedTable.ADDRESS, SizedTable.TYPE, SizedTable.ADDRESS, SizedTable.AMOUNT,
        SizedTable.AMOUNT, SizedTable.STATUS, SizedTable.STATUS};

    /** Wallet balance field */
    private final JLabel walletLabel;

    /** Safe balance field */
    private final JLabel safeLabel;

    /** Block field */
    private final JLabel blockLabel;

    /** Transaction table scroll pane */
    private final JScrollPane scrollPane;

    /** Transaction table */
    private final JTable table;

    /** Transaction table model */
    private final TransactionTableModel tableModel;

    /** Safe balance */
    private BigInteger safeBalance;

    /** Wallet balance */
    private BigInteger walletBalance;

    /**
     * Create the transaction panel
     *
     * @param       parentFrame     Parent frame
     */
    public TransactionPanel(JFrame parentFrame) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        //
        // Create the transaction table
        //
        tableModel = new TransactionTableModel(columnNames, columnClasses);
        table = new SizedTable(tableModel, columnTypes);
        table.setRowSorter(new TableRowSorter<>(tableModel));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        String frameSize = Main.properties.getProperty("window.main.size");
        if (frameSize != null) {
            int sep = frameSize.indexOf(',');
            int frameHeight = Integer.parseInt(frameSize.substring(sep+1));
            table.setPreferredScrollableViewportSize(new Dimension(
                        table.getPreferredScrollableViewportSize().width,
                        (frameHeight/table.getRowHeight())*table.getRowHeight()));
        }
        //
        // Create the table scroll pane
        //
        scrollPane = new JScrollPane(table);
        //
        // Create the status pane containing the Wallet balance, Safe balance and Chain block
        //
        JPanel statusPane = new JPanel();
        statusPane.setLayout(new BoxLayout(statusPane, BoxLayout.X_AXIS));
        statusPane.setBackground(Color.WHITE);
        walletLabel = new JLabel(getWalletText(), SwingConstants.CENTER);
        statusPane.add(walletLabel);
        safeLabel = new JLabel(getSafeText(), SwingConstants.CENTER);
        statusPane.add(safeLabel);
        blockLabel = new JLabel(getBlockText(), SwingConstants.CENTER);
        statusPane.add(blockLabel);
        //
        // Create the buttons (Move to Safe, Move to Wallet, Delete Transaction)
        //
        JPanel buttonPane = new ButtonPane(this, 15, new String[] {"Copy TxID", "copy txid"},
                                                     new String[] {"Move to Safe", "move to safe"},
                                                     new String[] {"Move to Wallet", "move to wallet"});
        buttonPane.setBackground(Color.white);
        //
        // Set up the content pane
        //
        add(statusPane);
        add(Box.createVerticalStrut(15));
        add(scrollPane);
        add(Box.createVerticalStrut(15));
        add(buttonPane);
    }

    /**
     * Action performed (ActionListener interface)
     *
     * @param       ae              Action event
     */
    @Override
    public void actionPerformed(ActionEvent ae) {
        //
        // "copy txid"      - Copy transaction ID to clipboard
        // "move to safe"   - Move transaction to the safe
        // "move to wallet" - Move transaction to the wallet
        //
        try {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(this, "No transaction selected", "Error", JOptionPane.ERROR_MESSAGE);
            } else {
                row = table.convertRowIndexToModel(row);
                String action = ae.getActionCommand();
                switch (action) {
                    case "copy txid":
                        String address = (String)tableModel.getValueAt(row, 1);
                        StringSelection sel = new StringSelection(address);
                        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                        cb.setContents(sel, null);
                        break;
                    case "move to safe":
                        if (moveToSafe(row)) {
                            tableModel.fireTableRowsUpdated(row, row);
                            walletLabel.setText(getWalletText());
                            safeLabel.setText(getSafeText());
                        }
                        break;
                    case "move to wallet":
                        if (moveToWallet(row)) {
                            tableModel.fireTableRowsUpdated(row, row);
                            walletLabel.setText(getWalletText());
                            safeLabel.setText(getSafeText());
                        }
                        break;
                }
            }
        } catch (WalletException exc) {
            Main.logException("Unable to update wallet", exc);
        } catch (Exception exc) {
            Main.logException("Exception while processing action event", exc);
        }
    }

    /**
     * The wallet has changed
     */
    public void walletChanged() {
        int row = table.getSelectedRow();
        tableModel.walletChanged();
        if (row >= 0 && row < table.getRowCount())
            table.setRowSelectionInterval(row, row);
        walletLabel.setText(getWalletText());
        safeLabel.setText(getSafeText());
    }

    /**
     * A new block has been received
     */
    public void statusChanged() {
        blockLabel.setText(getBlockText());
        tableModel.fireTableDataChanged();
    }

    /**
     * Move a transaction from the wallet to the safe
     *
     * We will not move a transaction unless it has spendable coins
     *
     * @param       row                 The transaction row
     * @return                          TRUE if the transaction was moved
     * @throws      WalletException     Unable to update wallet
     */
    private boolean moveToSafe(int row) throws WalletException {
        WalletTransaction tx = tableModel.getTransaction(row);
        if (!(tx instanceof ReceiveTransaction)) {
            JOptionPane.showMessageDialog(this, "The safe contains coins that you have received and not spent",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        ReceiveTransaction rcvTx = (ReceiveTransaction)tx;
        if (rcvTx.inSafe()) {
            JOptionPane.showMessageDialog(this, "The transaction is already in the safe",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (rcvTx.isSpent()) {
            JOptionPane.showMessageDialog(this, "The coins have already been spent",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        Parameters.wallet.setTxSafe(rcvTx.getTxHash(), rcvTx.getTxIndex(), true);
        rcvTx.setSafe(true);
        safeBalance = safeBalance.add(rcvTx.getValue());
        walletBalance = walletBalance.subtract(rcvTx.getValue());
        return true;
    }

    /**
     * Move a transaction from the safe to the wallet
     *
     * @param       row                 The transaction row
     * @return                          TRUE if the transaction was moved
     * @throws      WalletException     Unable to update wallet
     */
    private boolean moveToWallet(int row) throws WalletException {
        WalletTransaction tx = tableModel.getTransaction(row);
        if (!(tx instanceof ReceiveTransaction)) {
            JOptionPane.showMessageDialog(this, "The safe contains coins that you have received and not spent",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        ReceiveTransaction rcvTx = (ReceiveTransaction)tx;
        if (!rcvTx.inSafe()) {
            JOptionPane.showMessageDialog(this, "The transaction is not in the safe",
                                          "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        Parameters.wallet.setTxSafe(rcvTx.getTxHash(), rcvTx.getTxIndex(), false);
        walletBalance = walletBalance.add(rcvTx.getValue());
        safeBalance = safeBalance.subtract(rcvTx.getValue());
        rcvTx.setSafe(false);
        return true;
    }

    /**
     * Construct the wallet balance text
     */
    private String getWalletText() {
        return String.format("<html><h2>Wallet %s BTC</h2></html>", Main.satoshiToString(walletBalance));
    }

    /**
     * Construct the safe balance text
     */
    private String getSafeText() {
        return String.format("<html><h2>Safe %s BTC</h2></html>", Main.satoshiToString(safeBalance));
    }

    /**
     * Construct the chain block text
     */
    private String getBlockText() {
        return String.format("<html><h2>Block %d</h2></html>", Parameters.wallet.getChainHeight());
    }

    /**
     * Transaction table model
     */
    private class TransactionTableModel extends AbstractTableModel {

        /** Column names */
        private String[] columnNames;

        /** Column classes */
        private Class<?>[] columnClasses;

        /** Wallet transactions */
        private final List<WalletTransaction> txList = new LinkedList<>();

        /**
         * Create the transaction table model
         *
         * @param       columnName          Column names
         * @param       columnClasses       Column classes
         */
        public TransactionTableModel(String[] columnNames, Class<?>[] columnClasses) {
            super();
            if (columnNames.length != columnClasses.length)
                throw new IllegalArgumentException("Number of names not same as number of classes");
            this.columnNames = columnNames;
            this.columnClasses = columnClasses;
            buildTxList();
        }

        /**
         * Build the wallet transaction list and update the balances
         */
        private void buildTxList() {
            txList.clear();
            walletBalance = BigInteger.ZERO;
            safeBalance = BigInteger.ZERO;
            try {
                List<SendTransaction> sendList = Parameters.wallet.getSendTxList();
                for (SendTransaction sendTx : sendList) {
                    long txTime = sendTx.getTxTime();
                    walletBalance = walletBalance.subtract(sendTx.getValue()).subtract(sendTx.getFee());
                    boolean added = false;
                    for (int i=0; i<txList.size(); i++) {
                        if (txList.get(i).getTxTime() <= txTime) {
                            txList.add(i, sendTx);
                            added = true;
                            break;
                        }
                    }
                    if (!added)
                        txList.add(sendTx);
                }
                List<ReceiveTransaction> rcvList = Parameters.wallet.getReceiveTxList();
                for (ReceiveTransaction rcvTx : rcvList) {
                    if (rcvTx.isChange())
                        continue;
                    if (rcvTx.inSafe())
                        safeBalance = safeBalance.add(rcvTx.getValue());
                    else
                        walletBalance = walletBalance.add(rcvTx.getValue());
                    long txTime = rcvTx.getTxTime();
                    boolean added = false;
                    for (int i=0; i<txList.size(); i++) {
                        if (txList.get(i).getTxTime() <= txTime) {
                            txList.add(i, rcvTx);
                            added = true;
                            break;
                        }
                    }
                    if (!added)
                        txList.add(rcvTx);
                }
            } catch (WalletException exc) {
                Main.logException("Unable to build transaction list", exc);
            }
        }

        /**
         * Get the number of columns in the table
         *
         * @return                  The number of columns
         */
        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        /**
         * Get the column class
         *
         * @param       column      Column number
         * @return                  The column class
         */
        @Override
        public Class<?> getColumnClass(int column) {
            return columnClasses[column];
        }

        /**
         * Get the column name
         *
         * @param       column      Column number
         * @return                  Column name
         */
        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        /**
         * Get the number of rows in the table
         *
         * @return                  The number of rows
         */
        @Override
        public int getRowCount() {
            return txList.size();
        }

        /**
         * Get the value for a cell
         *
         * @param       row         Row number
         * @param       column      Column number
         * @return                  Returns the object associated with the cell
         */
        @Override
        public Object getValueAt(int row, int column) {
            if (row >= txList.size())
                throw new IndexOutOfBoundsException("Table row "+row+" is not valid");
            Object value;
            WalletTransaction tx = txList.get(row);
            //
            // Get the value for the requested cell
            //
            switch (column) {
                case 0:                                 // Date
                    value = new Date(tx.getTxTime()*1000);
                    break;
                case 1:                                 // Transaction ID
                    value = tx.getTxHash().toString();
                    break;
                case 2:                                 // Type
                    if (tx instanceof ReceiveTransaction)
                        value = "Received with";
                    else
                        value = "Sent to";
                    break;
                case 3:                                 // Name
                    value = null;
                    Address addr = tx.getAddress();
                    if (tx instanceof ReceiveTransaction) {
                        for (ECKey chkKey : Parameters.keys) {
                            if (Arrays.equals(chkKey.getPubKeyHash(), addr.getHash())) {
                                if (chkKey.getLabel().length() > 0)
                                    value = chkKey.getLabel();
                                break;
                            }
                        }
                    } else {
                        for (Address chkAddr : Parameters.addresses) {
                            if (Arrays.equals(chkAddr.getHash(), addr.getHash())) {
                                if (chkAddr.getLabel().length() > 0)
                                    value = chkAddr.getLabel();
                                break;
                            }
                        }
                    }
                    if (value == null)
                        value = addr.toString();
                    break;
                case 4:                                 // Amount
                    value = tx.getValue();
                    break;
                case 5:                                 // Fee
                    if (tx instanceof SendTransaction)
                        value = ((SendTransaction)tx).getFee();
                    else
                        value = null;
                    break;
                case 6:                                 // Location
                    if (tx instanceof ReceiveTransaction) {
                        if (((ReceiveTransaction)tx).inSafe())
                            value = "Safe";
                        else
                            value = "Wallet";
                    } else {
                        value = "";
                    }
                    break;
                case 7:                                 // Status
                    try {
                        if (tx instanceof ReceiveTransaction && ((ReceiveTransaction)tx).isSpent()) {
                            value = "Spent";
                        } else {
                            int depth = Parameters.wallet.getTxDepth(tx.getTxHash());
                            if ((tx instanceof ReceiveTransaction) && ((ReceiveTransaction)tx).isCoinBase()) {
                                if (depth == 0)
                                    value = "Pending";
                                else if (depth < Parameters.COINBASE_MATURITY)
                                    value = "Immature";
                                else
                                    value = "Mature";
                            } else if (depth == 0) {
                                value = "Pending";
                            } else if (depth < Parameters.TRANSACTION_CONFIRMED) {
                                value = "Building";
                            } else {
                                value = "Confirmed";
                            }
                        }
                    } catch (WalletException exc) {
                        Main.logException("Unable to get transaction depth", exc);
                        value = "Unknown";
                    }
                    break;
                default:
                    throw new IndexOutOfBoundsException("Table column "+column+" is not valid");
            }
                return value;
        }

        /**
         * Processes a wallet change
         */
        public void walletChanged() {
            buildTxList();
            fireTableDataChanged();
        }

        /**
         * Returns the wallet transaction for the specified table model row
         *
         * @param       row             Table model row
         * @return                      Wallet transaction
         */
        public WalletTransaction getTransaction(int row) {
            return txList.get(row);
        }

        /**
         * Deletes a wallet transaction
         *
         * @param       row             Table model row
         */
        public void deleteTransaction(int row) {
            txList.remove(row);
            fireTableRowsDeleted(row, row);
        }
    }
}
