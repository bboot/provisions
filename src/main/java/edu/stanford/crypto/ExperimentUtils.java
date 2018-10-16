/*
 * Decompiled with CFR 0_110.
 */
package edu.stanford.crypto;

import edu.stanford.crypto.bitcoin.SQLBlockchain;
import edu.stanford.crypto.bitcoin.SQLCustomerDatabase;
import edu.stanford.crypto.bitcoin.SQLPrivateKeyDatabase;
import org.bouncycastle.math.ec.ECPoint;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Semaphore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.swing.text.html.Option;
import java.util.Iterator;

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
        String jsonData = readFile(filename);
        JSONObject jsonObject = new JSONObject(jsonData);
        Iterator<String> keys = jsonObject.keys();
        Long bad = new Long(0);
        Long mine = new Long(0);
        while(keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith("03") && !key.startsWith("02") ||
                    key.length() != 66) {
                // Not sure what this is?
                bad++;
                continue;
            }
            JSONObject pubkey = jsonObject.getJSONObject(key);
//            System.out.println(pubkey.toString());
            if (!pubkey.isNull("priv_key")) {
                mine++;
            }
            // is this a compressed key?  Does it have trailing or
            // leading bytes?  Does it need to be byte-swapped?
            // How to test if it is a valid public key
        }
        System.out.println(String.format("Received %s keys, %d bad, %d mine",
                jsonObject.length(), bad, mine));
        // TODO: Load 'em up!
    }

}

