package com.contedevel.accountmanager

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.app.Activity
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.util.concurrent.TimeUnit


class AccountManagerPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "accountManager")
        channel.setMethodCallHandler(this)
    }

    private fun addAccount(call: MethodCall, result: Result) {
        activity?.let {
            val accountName = call.argument<String>(ACCOUNT_NAME)
            val packageName = call.argument<String>(PACKAGE_NAME)
            val token = call.argument<String>(ACCOUNT_TOKEN)
            val accountPlan = call.argument<String>(ACCOUNT_PLAN)
            val accountType = call.argument<String>(ACCOUNT_TYPE)
            val accountManager = AccountManager.get(it)
            val account = Account(accountName, packageName)
            val userData = Bundle()
            userData.putString("account_plan", accountPlan);
            userData.putString("account_type", accountType);
            val wasAdded = accountManager.addAccountExplicitly(account, token, userData)
            accountManager.setAuthToken(account, AUTHTOKEN_TYPE_FULL_ACCESS, token);
            accountManager.setUserData(account, "token_saved", token);
            if (wasAdded) {
                val bundle = Bundle();
                val intent = Intent();
                bundle.putString(AccountManager.KEY_AUTHTOKEN, token)
                bundle.putString(AccountManager.KEY_ACCOUNT_NAME, accountName)
                bundle.putString(AccountManager.KEY_ACCOUNT_TYPE, packageName)
                intent.putExtras(bundle)
                it.setResult(RESULT_OK, intent)
            } else {
                Toast.makeText(it.applicationContext, "ERROR", Toast.LENGTH_LONG).show()
            }
            result.success(wasAdded)
        }
    }

    private fun getAccounts(result: Result) {
        activity?.let {
            val accounts = AccountManager.get(it).accounts
            val list = mutableListOf<HashMap<String, String>>()
            for (account in accounts) {
                if (account != null) {
                    list.add(hashMapOf(
                            ACCOUNT_NAME to account.name,
                            PACKAGE_NAME to account.type,
                            ACCOUNT_TOKEN to AccountManager.get(it).getPassword(account),
                            ACCOUNT_PLAN to AccountManager.get(it).getUserData(account, "account_plan"),
                            ACCOUNT_TYPE to AccountManager.get(it).getUserData(account, "account_type")

                    ))
                }
            }
            result.success(list)
        }
    }

    private fun peekAccounts(result: Result) {
        if (activity != null) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AccountManager.newChooseAccountIntent(null, null, null, null, null, null, null)
            } else {
                AccountManager.newChooseAccountIntent(null, null, null, false, null, null, null, null)
            }
            ActivityCompat.startActivityForResult(activity!!, intent, REQUEST_CODE, null)
            result.success(true)
        } else {
            result.success(false)
        }
    }

    private fun removeAccount(call: MethodCall, result: Result) {
        activity?.let {
            val packageName = call.argument<String>(PACKAGE_NAME)
            val accountManager = AccountManager.get(it)
            val account = accountManager.getAccountsByType(packageName);
            for (i in account.indices) {
                accountManager.getAuthToken(account[i], AUTHTOKEN_TYPE_FULL_ACCESS, null, true, AccountManagerCallback { bundle ->
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                        val future = accountManager.removeAccount(account[i], {}, Handler())
                        result.success(future.getResult(2, TimeUnit.SECONDS))
                    } else {
                        val wasRemoved = accountManager.removeAccountExplicitly(account[i])
                        result.success(wasRemoved)
                    }
                }, null)
            }
        }
    }

    private fun invalidateAuthToken(call: MethodCall,result: Result) {
        activity?.let {
            val packageName = call.argument<String>(PACKAGE_NAME)
            val accountManager = AccountManager.get(it)
            val availableAccounts = accountManager.getAccountsByType(packageName)
            if (availableAccounts.isEmpty()) {
                result.success(false)
            } else {
                for (i in availableAccounts.indices) {
                    accountManager.getAuthToken(availableAccounts[i], AUTHTOKEN_TYPE_FULL_ACCESS, null, true, AccountManagerCallback { bundle ->
                        bundle.getResult(5L, TimeUnit.SECONDS);
                        val authToken = bundle.result.getString(AccountManager.KEY_AUTHTOKEN);
                        val accountType = bundle.result.getString(AccountManager.KEY_ACCOUNT_TYPE)
                        accountManager.invalidateAuthToken(accountType, authToken)
                        result.success(true);
                    }, null)
                }

            }
        }
    }

    private fun setAuthToken(call: MethodCall,result: Result) {
        activity?.let {
            val packageName = call.argument<String>(PACKAGE_NAME)
            val refreshToken = call.argument<String>(ACCOUNT_REFRESH_TOKEN)
            val accountManager = AccountManager.get(it)
            val availableAccounts = accountManager.getAccountsByType(packageName)
            if (availableAccounts.isEmpty()) {
                result.success(false)
            } else {
                for (i in availableAccounts.indices) {
                    accountManager.setAuthToken(availableAccounts[i], AUTHTOKEN_TYPE_FULL_ACCESS, refreshToken)
                    accountManager.setUserData(availableAccounts[i],"token_saved",refreshToken)
                    result.success(true)

                }

            }
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "addAccount" -> addAccount(call, result)
            "getAccounts" -> getAccounts(result)
            "removeAccount" -> removeAccount(call, result)
            "peekAccounts" -> peekAccounts(result);
            "invalidateAuthToken" -> invalidateAuthToken(call,result)
            "getAuthToken" -> getAuthToken(call, result)
            "setRefreshToken" -> setAuthToken(call,result)
            else -> result.notImplemented()
        }
    }

    private fun getAuthToken(call: MethodCall, result: Result) {
        activity?.let {
            var authData: Any? = ""
            val packageName = call.argument<String>(PACKAGE_NAME)
            val accountManager = AccountManager.get(it)
            val availableAccounts = accountManager.getAccountsByType(packageName)
            if (availableAccounts.isEmpty()) {
                result.success(null);
            } else {
                val name = arrayOfNulls<String>(availableAccounts.size)
                for (i in availableAccounts.indices) {
                    name[i] = availableAccounts[i].name
                    accountManager.getAuthToken(availableAccounts[i], AUTHTOKEN_TYPE_FULL_ACCESS, null, it, { bundle ->
                        val authToken = bundle.result.getString(AccountManager.KEY_AUTHTOKEN);
                        authData = authToken
                        Log.e("DATA", authData.toString() + " " + availableAccounts[0])
                        if (TextUtils.isEmpty(authToken)) {
                            result.success(null)
                        } else {
                            result.success(authData);
                        }

                    }, null)
                }

            }
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_CODE) {
            val account = if (resultCode == Activity.RESULT_OK && data != null) {
                hashMapOf(
                        "NAME" to data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME),
                        "TYPE" to data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
                )
            } else null
            channel.invokeMethod("onAccountPicked", account)
            return true
        }
        return false
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    // This static function is optional and equivalent to onAttachedToEngine. It supports the old
    // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
    // plugin registration via this function while apps migrate to use the new Android APIs
    // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
    //
    // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
    // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
    // depending on the user's project. onAttachedToEngine or registerWith must both be defined
    // in the same class.
    companion object {
        private const val REQUEST_CODE = 23
        private const val ACCOUNT_NAME = "account_name"
        private const val ACCOUNT_TYPE = "account_type"
        private const val ACCOUNT_TOKEN = "account_token"
        private const val ACCOUNT_PLAN = "account_plan"
        private const val PACKAGE_NAME = "package_name"
        private const val AUTHTOKEN_TYPE_FULL_ACCESS = "Full access"
        private const val ACCOUNT_REFRESH_TOKEN = "account_refresh_token"

        @Suppress("UNUSED")
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "accountManager")
            channel.setMethodCallHandler(AccountManagerPlugin())
        }
    }
}
