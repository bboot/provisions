/*
 * Decompiled with CFR 0_110.
 */
package edu.stanford.crypto.proof.assets;

import edu.stanford.crypto.ECConstants;
import edu.stanford.crypto.SQLDatabase;
import edu.stanford.crypto.bitcoin.SQLCustomerDatabase;
import edu.stanford.crypto.database.Database;
import edu.stanford.crypto.proof.Proof;
import edu.stanford.crypto.proof.binary.BinaryProof;
import org.bouncycastle.math.ec.ECPoint;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONString;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;

import static edu.stanford.crypto.ECConstants.*;
import static java.nio.file.Files.readAllLines;

public class AssetProof
implements Proof,
SQLDatabase {
    public static final String ADD_ADDRESS = "INSERT INTO asset_proof VALUES  (?, ?) ";
    public static final String GET_PROOF_SQL = "SELECT asset_proof.main_proof FROM asset_proof WHERE public_key= ?";
    public static final String LIST_PROOFS_SQL = "SELECT asset_proof.public_key,asset_proof.main_proof FROM asset_proof ";
    public static final String LIST_PROOFS_SQL_TO_JSON = "SELECT json_agg(json_build_object(" +
            "'public_key', asset_proof.public_key,'main_proof', asset_proof.main_proof)) FROM asset_proof ";
    public static final String READ_SIZE = "SELECT pg_size_pretty(pg_total_relation_size('public.asset_proof'))";
    private final PreparedStatement updateStatement;
    private final PreparedStatement readStatement;
    private final Connection connection = Database.getConnection();

    public AssetProof() throws SQLException {
        this.updateStatement = this.connection.prepareStatement(ADD_ADDRESS);
        this.readStatement = this.connection.prepareStatement(GET_PROOF_SQL);
    }

    public AssetProof(String filename) throws SQLException, IOException {
        this();
        this.importProofs(filename);
    }

    @Override
    public void close() throws SQLException {
        this.connection.close();
    }

    public void addAddressProof(ECPoint publicKey, AddressProof proof, AddressProofData addressProofData) {
        addAddressProof(publicKey, proof);
        if (!addressProofData.getPrivateKey().isPresent()) {
            return;
        }
        // XXX We are overloading the customer table for this
        BigInteger this_balance = addressProofData.getBalance();
        BigInteger this_balance_randomness = addressProofData.getBalanceRandomness();
        try {
            SQLCustomerDatabase customers = new SQLCustomerDatabase();
            BigInteger balance = customers.getBalance("Bob");
            byte[] curHsumV = customers.getBalanceRandomness("Bob");
            ECPoint cur_balanceRandomness = ECConstants.BITCOIN_CURVE.decodePoint(curHsumV);
            System.out.println("adding "+cur_balanceRandomness.toString());
            ECPoint balanceRandomness = cur_balanceRandomness.add(ECConstants.H.multiply(this_balance_randomness));
            customers.updateBalanceInfo("Bob", balance.add(this_balance), balanceRandomness.getEncoded(true));
        } catch (AssertionError e) {
            try {
                SQLCustomerDatabase customers = new SQLCustomerDatabase();
                ECPoint balanceRandomness = ECConstants.H.multiply(this_balance_randomness);
                customers.addBalanceInfo("Bob", this_balance, balanceRandomness.getEncoded(true));
            } catch(Exception e2) {
                System.out.println("some error "+e2);
            }
        } catch (Exception e) {
            System.out.println("Some other error "+e);
        }
    }

    public BigInteger getTotalAssets() {
        BigInteger balance = BigInteger.ZERO;
        try {
            SQLCustomerDatabase customers = new SQLCustomerDatabase();
            balance = customers.getBalance("Bob");
        } catch (Exception e) {
            // ignore
        }
        return balance;
    }

    public String getBalanceRandomnessString() {
       byte[] randomness = getBalanceRandomness();
       ECPoint balanceRandomness = ECConstants.BITCOIN_CURVE.decodePoint(randomness);
       return balanceRandomness.toString();
    }

    public byte[] getBalanceRandomness() {
        byte[] balance_randomness = new byte[0];
        try {
            SQLCustomerDatabase customers = new SQLCustomerDatabase();
            balance_randomness = customers.getBalanceRandomness("Bob");
        } catch (Exception e) {
            // ignore
        }
        return balance_randomness;
    }

    public void openedCommitmentInfo() {
        System.out.println(String.format("Proof size: %s", getSizeInfo()));
        System.out.println(String.format("Total Satoshi: %s", getTotalAssets().toString()));
        byte[] randomness = getBalanceRandomness();
        ECPoint balanceRandomness = ECConstants.BITCOIN_CURVE.decodePoint(randomness);
        System.out.println(String.format("Balance Randomness: %s", balanceRandomness.toString()));
        ECPoint Z_Assets = G.multiply(getTotalAssets()).add(balanceRandomness).normalize();
        System.out.println(String.format("Z_Assets: %s", Z_Assets.toString()));
    }

    public void addAddressProof(ECPoint publicKey, AddressProof proof) {
        try {
            this.updateStatement.setBytes(1, publicKey.getEncoded(true));
            this.updateStatement.setBytes(2, proof.serialize());
            this.updateStatement.executeUpdate();
        }
        catch (SQLException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    public AddressProof getAddressProof(ECPoint publicKey) {
        try {
            this.readStatement.setBytes(1, publicKey.getEncoded(true));
            ResultSet resultSet = this.readStatement.executeQuery();
            if (resultSet.next()) {
                byte[] proof = resultSet.getBytes(1);
                return new AddressProof(proof);
            }
        }
        catch (SQLException ex) {
            ex.printStackTrace();
        }
        throw new IllegalArgumentException("No such id " + publicKey.normalize());
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public void importProofs(String filename) throws IOException {
        List<String> lines = readAllLines(Paths.get(filename), StandardCharsets.UTF_8);
        String contents = lines.get(0);

        JSONArray jsonArray = new JSONArray(contents.trim());
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            Iterator<String> keys = jsonObject.keys();
            byte[] keyBytes = new byte[0];
            byte[] proofBytes = new byte[0];
            while(keys.hasNext()) {
                String key = keys.next();
                String value = (String)jsonObject.get(key);
                try {
                    System.out.println(key);
                    System.out.println(value);
                    if (key.equals("public_key")) {
                        keyBytes = this.hexStringToByteArray(value.substring(2));
                        this.updateStatement.setBytes(1, keyBytes);
                    }
                    if (key.equals("main_proof")) {
                        proofBytes = this.hexStringToByteArray(value.substring(2));
                        this.updateStatement.setBytes(2, proofBytes);
                    }
                    if (keyBytes.length > 0 && proofBytes.length > 0) {
                        this.updateStatement.executeUpdate();
                    }
                } catch (Exception e) {
                    System.out.println(key);
                    System.out.println(value);
                    e.printStackTrace();
                }
            }
        }
    }

    public void exportProofs(String filename) throws FileNotFoundException {
        try {
            this.connection.setAutoCommit(false);
            final ResultSet resultSet = this.connection.createStatement(
                                             ).executeQuery(LIST_PROOFS_SQL_TO_JSON);
            resultSet.setFetchSize(1000);
            boolean next = resultSet.next();
            if (next) {
                String  proof_json = resultSet.getString(1);
                //System.out.println(proof_json);
                try (PrintWriter out = new PrintWriter(filename)) {
                    out.println(proof_json);
                }
            }
        } catch (SQLException e) {
                e.printStackTrace();
                throw new IllegalStateException(e);
            }
    }

    public Iterator<AddressProofEntry> getAddressProofs() {
        try {
            this.connection.setAutoCommit(false);
            final ResultSet resultSet = this.connection.createStatement().executeQuery(LIST_PROOFS_SQL);
            resultSet.setFetchSize(1000);
            return new Iterator<AddressProofEntry>(){

                @Override
                public boolean hasNext() {
                    try {
                        boolean next = resultSet.next();
                        if (!next) {
                            AssetProof.this.connection.setAutoCommit(true);
                        }
                        return next;
                    }
                    catch (SQLException e) {
                        e.printStackTrace();
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public AddressProofEntry next() {
                    try {
                        byte[] publicKeyBytes = resultSet.getBytes(1);
                        byte[] proofBytes = resultSet.getBytes(2);
                        ECPoint publicKey = BITCOIN_CURVE.decodePoint(publicKeyBytes);
                        AddressProof addressProof = new AddressProof(proofBytes);
                        return new AddressProofEntry(publicKey, addressProof);
                    }
                    catch (SQLException e) {
                        e.printStackTrace();
                        throw new IllegalStateException(e);
                    }
                }
            };
        }
        catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }

    public Connection getConnection() {
        return this.connection;
    }

    public String getSizeInfo() {
        try {
            ResultSet resultSet = this.connection.createStatement().executeQuery(READ_SIZE);
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
        }
        catch (SQLException e) {
            e.printStackTrace();
        }
        throw new IllegalStateException("Couldn't run query "+READ_SIZE);
    }

}

