/*
 * Decompiled with CFR 0_110.
 */
package edu.stanford.crypto;

import com.oracle.javafx.jmx.json.JSONReader;
import edu.stanford.crypto.bitcoin.SQLBlockchain;
import edu.stanford.crypto.bitcoin.SQLCustomerDatabase;
import edu.stanford.crypto.bitcoin.SQLPrivateKeyDatabase;
import org.bouncycastle.jcajce.provider.asymmetric.util.EC5Util;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.ECPointUtil;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECNamedCurveSpec;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Semaphore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONString;
import org.json.simple.JSONStreamAware;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.swing.text.html.Option;
import java.util.Iterator;

import static edu.stanford.crypto.ECConstants.BITCOIN_CURVE;
import static edu.stanford.crypto.ECConstants.N;

public class ExperimentUtils {
    public static void generateRandomCustomers(int numCustomers, int maxBits) throws SQLException {
        try {
            SQLCustomerDatabase database = new SQLCustomerDatabase();
            try {
                database.truncate();
            } finally {

                database.close();

            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        Random rng = new Random();
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        Semaphore semaphore = new Semaphore(0);
        int process = 0;
        while (process < availableProcessors) {
            SQLCustomerDatabase processDb = new SQLCustomerDatabase();
            int processId = process++;
            ForkJoinTask.adapt(() -> {
                        for (int i = processId; i < numCustomers; i += availableProcessors) {
                            String customerId = "C" + i;
                            BigInteger balance = rng.nextDouble() > 0.9 ? new BigInteger(maxBits, rng) : new BigInteger(10, rng);
                            processDb.addBalance(customerId, balance);
                        }
                        try {
                            processDb.close();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        semaphore.release();
                    }
            ).fork();
        }
        try {
            semaphore.acquire(availableProcessors);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void generatePublicKeys(int numAddresses) throws SQLException {
        SQLBlockchain blockchain = new SQLBlockchain();
        try {
            blockchain.truncate();
        } finally {

            blockchain.close();
        }
        Random rng = new Random();
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        Semaphore semaphore = new Semaphore(0);
        int process = 0;
        while (process < availableProcessors) {
            SQLBlockchain processBlockchain = new SQLBlockchain();
            SQLPrivateKeyDatabase privateKeyDatabase = new SQLPrivateKeyDatabase();
            int processId = process++;
            ForkJoinTask.adapt(() -> {
                        try {
                            for (int i = processId; i < numAddresses; i += availableProcessors) {
                                BigInteger privateKey = new BigInteger(256, rng);
                                ECPoint publicKey = ECConstants.G.multiply(privateKey);
                                BigInteger balance = new BigInteger(10, rng);
                                processBlockchain.addEntry(publicKey, balance);
                                if (rng.nextDouble() >= 0.05) continue;
                                privateKeyDatabase.store(publicKey, privateKey);
                            }
                        } finally {
                            try {
                                privateKeyDatabase.close();
                                processBlockchain.close();
                            } catch (SQLException e) {
                                e.printStackTrace();
                            }

                            semaphore.release();
                        }
                    }
            ).fork();
        }
        try {
            semaphore.acquire(availableProcessors);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static String readFile(String filename) {
        String result = "";
        try {
            BufferedReader br = new BufferedReader(new FileReader(filename));
            StringBuilder sb = new StringBuilder();
            String line = br.readLine();
            while (line != null) {
                sb.append(line);
                line = br.readLine();
            }
            result = sb.toString();
        } catch(Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    public static void loadFromJson(String filename) throws SQLException,
            IOException {
        SQLBlockchain blockchain = new SQLBlockchain();
        try {
            blockchain.truncate();
        } finally {
            blockchain.close();
        }
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        String jsonData = readFile(filename);
        JSONObject jsonObject = new JSONObject(jsonData);
        Iterator<String> entries = jsonObject.keys();
        Long uncompressed = new Long(0);
        Long bad = new Long(0);
        Long mine = new Long(0);
        BigInteger my_balance = BigInteger.valueOf(0);
        SQLBlockchain processBlockchain = new SQLBlockchain();
        SQLPrivateKeyDatabase privateKeyDatabase = new SQLPrivateKeyDatabase();
        while(entries.hasNext()) {
            String pubkey_entry = entries.next();
            System.out.println("pubkey_entry: "+pubkey_entry);
            if (!pubkey_entry.startsWith("03") && !pubkey_entry.startsWith("02") ||
                    pubkey_entry.length() != 64 + 2) {
                String prefix = pubkey_entry.substring(0, 2);
                if (!prefix.equals("04") || pubkey_entry.length() != 64 + 64 + 2) {
                    bad++;
                    continue;
                }
                BigInteger x = new BigInteger(pubkey_entry.substring(2, 2 + 64), 16);
                BigInteger y = new BigInteger(pubkey_entry.substring(2 + 64), 16);
                uncompressed++;
            }
            ECPoint publicKey;
            try {
                BigInteger encoded = new BigInteger(pubkey_entry, 16);
                publicKey = BITCOIN_CURVE.decodePoint(encoded.toByteArray());
            } catch(Exception e) {
                System.out.println("got exception"+e.toString());
                continue;
            }
            BigDecimal balance = new BigDecimal(0.0);
            JSONObject item = jsonObject.getJSONObject(pubkey_entry);

            // get transactions
            JSONObject txs = item.getJSONObject("txs");
            Iterator<String> tx_hashes = txs.keys();
            while (tx_hashes.hasNext()) {
                String tx_hash = tx_hashes.next();
                System.out.println(tx_hash);
                JSONArray vouts = txs.getJSONArray(tx_hash);
                System.out.println(vouts);
                for (int i=0; i<vouts.length(); i++) {
                    JSONObject vout = vouts.getJSONObject(i);
                    BigDecimal amount = vout.getBigDecimal("amount");
                    balance = balance.add(amount);
                }
            }
            System.out.println("balance: "+balance);
            BigInteger balance_satoshis = balance.multiply(
                    new BigDecimal(100*1000*1000)).toBigInteger();
            System.out.println("satoshis: "+balance_satoshis);
            processBlockchain.addEntry(publicKey, balance_satoshis);

            // get private key, if any
//            System.out.println(item.toString());
            if (!item.isNull("priv_key")) {
                /**
                 * The bitcoind "dumpprivkey" command produces the private key bytes with a
                 * header byte and 4 checksum bytes at the end. If there are 33 private
                 * key bytes instead of 32, then the last byte is a discriminator value
                 * for the compressed pubkey.
                 * --bitcoinj
                 */
                String priv_key = item.getString("priv_key");
//                System.out.println("priv_key: "+priv_key.length()+" "+priv_key);
                priv_key = priv_key.substring(2);
                // 4 checksum bytes + 1 discriminator value
                priv_key = priv_key.substring(0, priv_key.length() - 5*2);
                BigInteger privateKey = new BigInteger(priv_key, 16);
                privateKeyDatabase.store(publicKey, privateKey);
                mine++;
                my_balance = balance_satoshis.add(my_balance);
                System.out.println("total balance:" + my_balance);
            }
        }
        try {
            privateKeyDatabase.close();
            processBlockchain.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        System.out.println(String.format("Received %s keys, %d bad, %d uncompressed, %d mine",
                jsonObject.length(), bad, uncompressed, mine));
    }

    public static byte[] concatenate(byte[] a, byte[] b) {
        // create a destination array that is the size of the two arrays
        byte[] destination = new byte[a.length + b.length];
        // copy a into start of destination (from pos 0, copy a.length bytes)
        System.arraycopy(a, 0, destination, 0, a.length);
        // copy b into end of destination (from pos a.length, copy b.length bytes)
        System.arraycopy(b, 0, destination, a.length, b.length);
        return destination;
    }

    public static void printBytes(String x) {
        for (int j=0; j<x.getBytes().length; j++) {
            System.out.format("%02X ", x.getBytes()[j]);
        }
    }
}

