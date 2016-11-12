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
import org.ScripterRon.BitcoinCore.FilterLoadMessage;
import org.ScripterRon.BitcoinCore.Message;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;

/**
 * ReceiveAddressDialog displays a table containing labels and associated receive addresses.
 * The user can create a new entry, edit an entry or delete an entry.
 *
 * A receive address represents a public/private key pair owned by this wallet.  It is used
 * to receive coins.
 */
public class ReceiveAddressDialog extends JDialog implements ActionListener {

    /** Address table column classes */
    private static final Class<?>[] columnClasses = {
        String.class, String.class, String.class};

    /** Address table column names */
    private static final String[] columnNames = {
        "Name", "P2PKH Address", "P2SH Address"};

    /** Transaction table column types */
    private static final int[] columnTypes = {
        SizedTable.NAME, SizedTable.ADDRESS, SizedTable.ADDRESS};

    /** Address table model */
    private final AddressTableModel tableModel;

    /** Address table */
    private final JTable table;

    /** Address table scroll pane */
    private final JScrollPane scrollPane;

    /**
     * Create the dialog
     *
     * @param       parent          Parent frame
     */
    public ReceiveAddressDialog(JFrame parent) {
        super(parent, "Receive Addresses", Dialog.ModalityType.DOCUMENT_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        //
        // Create the address table
        //
        tableModel = new AddressTableModel(columnNames, columnClasses);
        table = new SizedTable(tableModel, columnTypes);
        table.setRowSorter(new TableRowSorter<>(tableModel));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setPreferredScrollableViewportSize(new Dimension(700, 600));
        //
        // Create the table scroll pane
        //
        scrollPane = new JScrollPane(table);
        //
        // Create the table pane
        //
        JPanel tablePane = new JPanel();
        tablePane.setBackground(Color.WHITE);
        tablePane.add(scrollPane);
        //
        // Create the buttons (New, Copy, Edit, Done)
        //
        JPanel buttonPane = new ButtonPane(this, 10, new String[] {"New", "new"},
                                                     new String[] {"Copy P2PKH", "copy-p2pkh"},
                                                     new String[] {"Copy P2SH", "copy-p2sh"},
                                                     new String[] {"Edit", "edit"},
                                                     new String[] {"Done", "done"});
        buttonPane.setBackground(Color.white);
        //
        // Set up the content pane
        //
        JPanel contentPane = new JPanel();
        contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
        contentPane.setOpaque(true);
        contentPane.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        contentPane.setBackground(Color.WHITE);
        contentPane.add(tablePane);
        contentPane.add(buttonPane);
        setContentPane(contentPane);
    }

    /**
     * Show the address list dialog
     *
     * @param       parent              Parent frame
     */
    public static void showDialog(JFrame parent) {
        try {
            JDialog dialog = new ReceiveAddressDialog(parent);
            dialog.pack();
            dialog.setLocationRelativeTo(parent);
            dialog.setVisible(true);
        } catch (Exception exc) {
            Main.logException("Exception while displaying dialog", exc);
        }
    }

    /**
     * Action performed (ActionListener interface)
     *
     * @param   ae              Action event
     */
    @Override
    public void actionPerformed(ActionEvent ae) {

        //
        // "new"        - Create a new address entry
        // "copy-p2pkh" - Copy a P2PKH address to the system clipbboard
        // "copy-p2sh"  - Copy a P2SH address to the system clipboard
        // "edit"       - Edit an address entry
        // "done"       - All done
        //
        try {
            String action = ae.getActionCommand();
            if (action.equals("done")) {
                setVisible(false);
                dispose();
            } else if (action.equals("new")) {
                ECKey key = new ECKey();
                editKey(key, -1);
            } else {
                int row = table.getSelectedRow();
                if (row < 0) {
                    JOptionPane.showMessageDialog(this, "No entry selected", "Error", JOptionPane.ERROR_MESSAGE);
                } else {
                    row = table.convertRowIndexToModel(row);
                    switch (action) {
                        case "copy-p2pkh":
                            String address = (String)tableModel.getValueAt(row, 1);
                            StringSelection sel = new StringSelection(address);
                            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                            cb.setContents(sel, null);
                            break;
                        case "copy-p2sh":
                            address = (String)tableModel.getValueAt(row, 2);
                            sel = new StringSelection(address);
                            cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                            cb.setContents(sel, null);
                            break;
                        case "edit":
                            ECKey key = Parameters.keys.get(row);
                            editKey(key, row);
                            break;
                    }
                }
            }
        } catch (WalletException exc) {
            Main.logException("Unable to update wallet database", exc);
        } catch (Exception exc) {
            Main.logException("Exception while processing action event", exc);
        }
    }

    /**
     * Edit the key
     *
     * @param       key                 Key
     * @param       row                 Table row or -1 if the key is not in the table
     * @throws      WalletException     Unable to update database
     */
    private void editKey(ECKey key, int row) throws WalletException {
        //
        // Show the address edit dialog and validate the return label
        //
        Address addr = key.toAddress();
        while (true) {
            addr = AddressEditDialog.showDialog(this, addr, false);
            if (addr == null)
                break;
            String label = addr.getLabel();
            boolean valid = true;
            synchronized(Parameters.lock) {
                for (ECKey chkKey : Parameters.keys) {
                    if (chkKey == key)
                        continue;
                    if (chkKey.getLabel().compareToIgnoreCase(label) == 0) {
                        JOptionPane.showMessageDialog(this, "Duplicate name specified", "Error",
                                                      JOptionPane.ERROR_MESSAGE);
                        valid = false;
                        break;
                    }
                }
                //
                // Second pass inserts the updated key in the list sorted by label
                //
                if (valid) {
                    if (row >= 0)
                        Parameters.keys.remove(row);
                    boolean added = false;
                    for (int i=0; i<Parameters.keys.size(); i++) {
                        ECKey chkKey = Parameters.keys.get(i);
                        if (chkKey.getLabel().compareToIgnoreCase(label) > 0) {
                            key.setLabel(label);
                            Parameters.keys.add(i, key);
                            added = true;
                            break;
                        }
                    }
                    if (!added) {
                        key.setLabel(label);
                        Parameters.keys.add(key);
                    }
                }
            }
            if (valid) {
                //
                // Update the database and load a new bloom filter if we generated a new key
                //
                if (row >= 0) {
                    Parameters.wallet.setKeyLabel(key);
                } else {
                    Parameters.wallet.storeKey(key);
                    Parameters.bloomFilter.insert(key.getPubKey());
                    Parameters.bloomFilter.insert(key.getPubKeyHash());
                    Message filterMsg = FilterLoadMessage.buildFilterLoadMessage(null, Parameters.bloomFilter);
                    Parameters.networkHandler.broadcastMessage(filterMsg);
                }
                //
                // Update the table
                //
                tableModel.fireTableDataChanged();
                break;
            }
        }
    }

    /**
     * AddressTableModel is the table model for the address dialog
     */
    private class AddressTableModel extends AbstractTableModel {

        /** Column names */
        private String[] columnNames;

        /** Column classes */
        private Class<?>[] columnClasses;

        /**
         * Create the table model
         *
         * @param       columnNames     Column names
         * @param       columnClasses   Column classes
         */
        public AddressTableModel(String[] columnNames, Class<?>[] columnClasses) {
            super();
            if (columnNames.length != columnClasses.length)
                throw new IllegalArgumentException("Number of names not same as number of classes");
            this.columnNames = columnNames;
            this.columnClasses = columnClasses;
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
            return Parameters.keys.size();
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
            if (row >= Parameters.keys.size())
                throw new IndexOutOfBoundsException("Table row "+row+" is not valid");
            Object value;
            ECKey key = Parameters.keys.get(row);
            switch (column) {
                case 0:
                    value = key.getLabel();
                    break;
                case 1:
                    value = new Address(Address.AddressType.P2PKH, key.getPubKeyHash()).toString();
                    break;
                case 2:
                    value = new Address(Address.AddressType.P2SH, key.getScriptHash()).toString();
                    break;
                default:
                    throw new IndexOutOfBoundsException("Table column "+column+" is not valid");
            }
            return value;
        }
    }
}
