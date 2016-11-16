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
import org.ScripterRon.BitcoinCore.AddressFormatException;
import org.ScripterRon.BitcoinCore.ECException;
import org.ScripterRon.BitcoinCore.InventoryItem;
import org.ScripterRon.BitcoinCore.InventoryMessage;
import org.ScripterRon.BitcoinCore.Message;
import org.ScripterRon.BitcoinCore.ScriptException;
import org.ScripterRon.BitcoinCore.SignedInput;
import org.ScripterRon.BitcoinCore.Transaction;
import org.ScripterRon.BitcoinCore.TransactionOutput;
import org.ScripterRon.BitcoinCore.VerificationException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.WindowConstants;

import java.awt.Dialog;
import java.awt.Dimension;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * SendDialog will create a new transaction to send coins to a specified recipient.  Transactions in the safe and
 * unconfirmed transactions will not be selected as inputs.  Inputs will be selected starting with the smallest
 * value and then increasing in value until the send amount has been satisfied.
 */
public class SendDialog extends JDialog implements ActionListener {

    /** Address field */
    private final JComboBox<Object> addressField;

    /** Amount field */
    private final JTextField amountField;

    /** Fee field */
    private final JTextField feeField;

    /** Send sendAddress */
    private Address sendAddress;

    /** Send amount */
    private BigInteger sendAmount;

    /** Send fee */
    private BigInteger sendFee;

    /**
     * Create the dialog
     *
     * @param       parent          Parent frame
     */
    public SendDialog(JFrame parent) {
        super(parent, "Send Coins", Dialog.ModalityType.DOCUMENT_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        //
        // Create the address field
        //
        if (Parameters.addresses.isEmpty()) {
            addressField = new JComboBox<>();
        } else {
            String[] addrList = new String[Parameters.addresses.size()];
            int index = 0;
            for (Address addr : Parameters.addresses)
                addrList[index++] = addr.getLabel();
            addressField = new JComboBox<>(addrList);
        }
        addressField.setEditable(true);
        addressField.setSelectedIndex(-1);
        addressField.setPreferredSize(new Dimension(340, 25));
        JPanel addressPane = new JPanel();
        addressPane.add(new JLabel("Address  ", JLabel.RIGHT));
        addressPane.add(addressField);
        //
        // Create the amount field
        //
        amountField = new JTextField("", 15);
        JPanel amountPane = new JPanel();
        amountPane.add(new JLabel("Amount  ", JLabel.RIGHT));
        amountPane.add(amountField);
        //
        // Create the fee field
        //
        String feeString = Main.properties.getProperty("send.fee");
        if (feeString == null) {
            feeString = "0.0001";
        }
        feeField = new JTextField(feeString, 10);
        JPanel feePane = new JPanel();
        feePane.add(new JLabel("Fee  ", JLabel.RIGHT));
        feePane.add(feeField);
        //
        // Create the buttons (Send, Done)
        //
        JPanel buttonPane = new ButtonPane(this, 10, new String[] {"Send", "send"},
                                                     new String[] {"Done", "done"});
        //
        // Set up the content pane
        //
        JPanel contentPane = new JPanel();
        contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
        contentPane.setOpaque(true);
        contentPane.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        contentPane.add(addressPane);
        contentPane.add(Box.createVerticalStrut(15));
        contentPane.add(amountPane);
        contentPane.add(Box.createVerticalStrut(15));
        contentPane.add(feePane);
        contentPane.add(Box.createVerticalStrut(15));
        contentPane.add(buttonPane);
        setContentPane(contentPane);
    }

    /**
     * Show the send dialog
     *
     * @param       parent              Parent frame
     */
    public static void showDialog(JFrame parent) {
        try {
            JDialog dialog = new SendDialog(parent);
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
        // "send"   - Send coins
        // "done"   - All done
        //
        try {
            String action = ae.getActionCommand();
            switch (action) {
                case "send":
                    if (checkFields()) {
                        String confirmText = String.format("Do you want to send %s BTC?",
                                                           Main.satoshiToString(sendAmount));
                        if (JOptionPane.showConfirmDialog(this, confirmText, "Send Coins", JOptionPane.YES_NO_OPTION,
                                                          JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
                            sendCoins();
                        }
                    }
                    break;
                case "done":
                    setVisible(false);
                    dispose();
                    break;
            }
        } catch (NumberFormatException exc) {
            JOptionPane.showMessageDialog(this, "Invalid numeric value entered", "Error",
                                          JOptionPane.ERROR_MESSAGE);
        } catch (AddressFormatException exc) {
            JOptionPane.showMessageDialog(this, "Send address is not valid", "Error", JOptionPane.ERROR_MESSAGE);
        } catch (WalletException exc) {
            Main.logException("Unable to process send request", exc);
        } catch (Exception exc) {
            Main.logException("Exception while processing action event", exc);
        }
    }

    /**
     * Verify the fields
     *
     * @return                                  TRUE if the fields are valid
     * @throws      AddressFormatException      Send address is not valid
     * @throws      NumberFormatException       Invalid numeric value entered
     */
    private boolean checkFields() throws AddressFormatException, NumberFormatException {
        //
        // Get the send address
        //
        String sendString = (String)addressField.getSelectedItem();
        if (sendString == null) {
            JOptionPane.showMessageDialog(this, "You must enter a send address", "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        int index = addressField.getSelectedIndex();
        if (index < 0)
            sendAddress = new Address(sendString);
        else
            sendAddress = Parameters.addresses.get(index);
        if (sendAddress.getType() == Address.AddressType.P2SH && !Parameters.witnessActivated) {
            JOptionPane.showMessageDialog(this, "Segregated Witness is not activated yet",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        //
        // Get the send amount
        //
        String amountString = amountField.getText();
        if (amountString.isEmpty()) {
            JOptionPane.showMessageDialog(this, "You must enter the amount to send", "Error",
                                          JOptionPane.ERROR_MESSAGE);
            return false;
        }
        sendAmount = Main.stringToSatoshi(amountString);
        if (sendAmount.compareTo(Parameters.DUST_TRANSACTION) < 0) {
            JOptionPane.showMessageDialog(this, String.format("The minimum amount you can send is %s BTC",
                                                              Main.satoshiToString(Parameters.DUST_TRANSACTION)),
                                                              "ERROR", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        //
        // Get the fee amount
        //
        String feeString = feeField.getText();
        if (feeString.isEmpty()) {
            JOptionPane.showMessageDialog(this, "You must enter a transaction fee", "Enter",
                                          JOptionPane.ERROR_MESSAGE);
            return false;
        }
        sendFee = Main.stringToSatoshi(feeString);
        if (sendFee.compareTo(Parameters.MIN_TX_FEE) < 0) {
            JOptionPane.showMessageDialog(this, String.format("The minimun transaction fee is %s BTC",
                                                              Main.satoshiToString(Parameters.MIN_TX_FEE)),
                                                              "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        Main.properties.setProperty("send.fee", feeString);
        return true;
    }

    /**
     * Send the coins
     *
     * @throws      WalletException     Unable to process send request
     */
    private void sendCoins() throws WalletException {
        //
        // Get the list of available inputs
        //
        List<SignedInput> inputList = BuildInputList.buildSignedInputs();
        //
        // Build the new transaction
        //
        Transaction tx = null;
        while (true) {
            BigInteger totalAmount = sendAmount.add(sendFee);
            List<SignedInput> inputs = new ArrayList<>(inputList.size());
            for (SignedInput input : inputList) {
                inputs.add(input);
                totalAmount = totalAmount.subtract(input.getValue());
                if (totalAmount.signum() <= 0)
                    break;
            }
            if (totalAmount.signum() > 0) {
                JOptionPane.showMessageDialog(this, "There are not enough confirmed coins available",
                                              "Error", JOptionPane.ERROR_MESSAGE);
                break;
            }
            List<TransactionOutput> outputs = new ArrayList<>(2);
            outputs.add(new TransactionOutput(0, sendAmount, sendAddress));
            BigInteger change = totalAmount.negate();
            if (change.compareTo(Parameters.DUST_TRANSACTION) > 0) {
                Address.AddressType type = sendAddress.getType();
                byte[] hash = (type==Address.AddressType.P2SH ?
                        Parameters.changeKey.getScriptHash() : Parameters.changeKey.getPubKeyHash());
                outputs.add(new TransactionOutput(1, change, new Address(type, hash)));
            }
            //
            // Create the new transaction using the supplied inputs and outputs
            //
            try {
                tx = new Transaction(inputs, outputs, Parameters.wallet.getChainHeight());
            } catch (ECException | ScriptException | VerificationException exc) {
                throw new WalletException("Unable to create transaction", exc);
            }
            //
            // The minimum fee increases for every 1000 bytes of serialized transaction data.  We
            // will need to increase the send fee if it doesn't cover the minimum fee.
            //
            int length = tx.getBytes().length;
            BigInteger minFee = BigInteger.valueOf(length/1000+1).multiply(Parameters.MIN_TX_FEE);
            if (minFee.compareTo(sendFee) <= 0)
                break;
            sendFee = minFee;
            tx = null;
        }
        //
        // Store the new transaction in the database and broadcast it to our peers
        //
        if (tx != null) {
            Parameters.databaseHandler.processTransaction(tx);
            List<InventoryItem> invList = new ArrayList<>(1);
            invList.add(new InventoryItem(InventoryItem.INV_TX, tx.getHash()));
            Message invMsg = InventoryMessage.buildInventoryMessage(null, invList);
            Parameters.networkHandler.broadcastMessage(invMsg);
            JOptionPane.showMessageDialog(this, String.format("Transaction broadcast to peer nodes\n%s",
                                          tx.getHash()), "Transaction Broadcast", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
