package org.secuso.privacyfriendlyfinance.domain;

import android.arch.persistence.room.Database;
import android.arch.persistence.room.Room;
import android.arch.persistence.room.RoomDatabase;
import android.arch.persistence.room.TypeConverters;
import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.commonsware.cwac.saferoom.SafeHelperFactory;

import org.secuso.privacyfriendlyfinance.activities.helper.CommunicantAsyncTask;
import org.secuso.privacyfriendlyfinance.activities.helper.TaskListener;
import org.secuso.privacyfriendlyfinance.domain.access.AccountDao;
import org.secuso.privacyfriendlyfinance.domain.access.CategoryDao;
import org.secuso.privacyfriendlyfinance.domain.access.TransactionDao;
import org.secuso.privacyfriendlyfinance.domain.convert.LocalDateConverter;
import org.secuso.privacyfriendlyfinance.domain.legacy.MigrationFromUnencrypted;
import org.secuso.privacyfriendlyfinance.domain.model.Account;
import org.secuso.privacyfriendlyfinance.domain.model.Category;
import org.secuso.privacyfriendlyfinance.domain.model.Transaction;
import org.secuso.privacyfriendlyfinance.helpers.KeyStoreHelper;
import org.secuso.privacyfriendlyfinance.helpers.SharedPreferencesManager;

import java.io.File;

@Database(
    entities = {Account.class, Category.class, Transaction.class},
    exportSchema = false,
    version = 6
)
@TypeConverters({LocalDateConverter.class})
public abstract class FinanceDatabase extends RoomDatabase {
    private static final String DB_NAME = "encryptedDB";
    private static InitDatabaseTask initTask;
    private static FinanceDatabase instance;

    public abstract TransactionDao transactionDao();
    public abstract CategoryDao categoryDao();
    public abstract AccountDao accountDao();

    public static FinanceDatabase getInstance() {
        return instance;
    }

    public static CommunicantAsyncTask<?, FinanceDatabase> connect(Context context, TaskListener listener) {
        if (initTask == null) {
            initTask = new InitDatabaseTask(context, DB_NAME);
            if (listener != null) initTask.addListener(listener);
            initTask.execute();
        }
        return initTask;
    }


    private static class InitDatabaseTask extends CommunicantAsyncTask<Void, FinanceDatabase> {
        private static final String KEY_ALIAS = "financeDatabaseKey";
        private Context context;
        private String dbName;

        public InitDatabaseTask(Context context, String dbName) {
            this.context = context;
            this.dbName = dbName;
        }

        private void deleteDatabaseFile() {
            File databaseDir = new File(context.getApplicationInfo().dataDir + "/databases");
            File[] files = databaseDir.listFiles();
            if(files != null) {
                for(File f: files) {
                    if(!f.isDirectory() && f.getName().startsWith(DB_NAME)) {
                        f.delete();
                    }
                }
            }
        }

        private boolean dbFileExists() {
            File databaseFile = new File(context.getApplicationInfo().dataDir + "/databases/" + DB_NAME);
            return databaseFile.exists() && databaseFile.isFile();
        }

        @Override
        protected FinanceDatabase doInBackground(Void... voids) {
            try {

                publishProgress(0.0);
                publishOperation("init key store");
                KeyStoreHelper keystore = new KeyStoreHelper(KEY_ALIAS);
                String passphrase = SharedPreferencesManager.getDbPassphrase();

                if (!keystore.keyExists()) {
                    keystore.generateKey(context);
                    if (passphrase != null) {
                        Log.w("OpenDatabase", "database passphrase could not be recovered");
                        SharedPreferencesManager.removeDbPassphrase();
                    }
                }

                publishProgress(.2);

                if (passphrase == null) {
                    deleteDatabaseFile();
                    publishOperation("create passphrase");
                    passphrase = keystore.createPassphrase();
                    SharedPreferencesManager.setDbPassphrase(passphrase);
                }

                publishProgress(.4);
                publishOperation("decrypt passphrase");
                byte[] decryptedPassphrase = keystore.rsaDecrypt(Base64.decode(passphrase, Base64.DEFAULT));

                char[] charPassphrase = new char[decryptedPassphrase.length];
                for (int i = 0; i < decryptedPassphrase.length; ++i) {
                    charPassphrase[i] = (char) (decryptedPassphrase[i] & 0xFF);
                }

                publishProgress(.6);
                if (dbFileExists()) {
                    publishOperation("open database");
                } else {
                    publishOperation("create and open database");
                }
                FinanceDatabase.instance = Room.databaseBuilder(context, FinanceDatabase.class, dbName)
                        .openHelperFactory(new SafeHelperFactory(charPassphrase))
                        .fallbackToDestructiveMigration()
                        .build();

                if (FinanceDatabase.instance.accountDao().count() == 0) {
                    Account defaultAccount = new Account("DefaultAccount");
                    defaultAccount.setId(0L);
                    FinanceDatabase.instance.accountDao().insert(defaultAccount);
                }

                if (MigrationFromUnencrypted.legacyDatabaseExists(context)) {
                    publishProgress(.8);
                    publishOperation("migrate database");
                    MigrationFromUnencrypted.migrateTo(FinanceDatabase.instance, context);
                    MigrationFromUnencrypted.deleteLegacyDatabase(context);
                }
                return FinanceDatabase.instance;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
