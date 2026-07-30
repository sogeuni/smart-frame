package dev.sogn.smartframe

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object OneDriveAuthManager {
    private val scopes = listOf("Files.Read")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var application: ISingleAccountPublicClientApplication? = null
    private var initializationStarted = false
    private val initializationCallbacks =
        mutableListOf<(Result<ISingleAccountPublicClientApplication>) -> Unit>()

    fun initialize(
        context: Context,
        callback: (Result<ISingleAccountPublicClientApplication>) -> Unit,
    ) {
        synchronized(lock) {
            application?.let {
                mainHandler.post { callback(Result.success(it)) }
                return
            }
            initializationCallbacks += callback
            if (initializationStarted) return
            initializationStarted = true
        }

        if (!isConfigured(context)) {
            finishInitialization(
                Result.failure(
                    IllegalStateException(context.getString(R.string.onedrive_not_configured)),
                ),
            )
            return
        }

        PublicClientApplication.createSingleAccountPublicClientApplication(
            context.applicationContext,
            R.raw.auth_config_single_account,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(app: ISingleAccountPublicClientApplication) {
                    synchronized(lock) {
                        application = app
                    }
                    finishInitialization(Result.success(app))
                }

                override fun onError(exception: MsalException) {
                    finishInitialization(Result.failure(exception))
                }
            },
        )
    }

    fun loadAccount(context: Context, callback: (Result<IAccount?>) -> Unit) {
        initialize(context) { appResult ->
            appResult.fold(
                onSuccess = { app ->
                    app.getCurrentAccountAsync(
                        object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                            override fun onAccountLoaded(activeAccount: IAccount?) {
                                deliver(callback, Result.success(activeAccount))
                            }

                            override fun onAccountChanged(
                                priorAccount: IAccount?,
                                currentAccount: IAccount?,
                            ) {
                                deliver(callback, Result.success(currentAccount))
                            }

                            override fun onError(exception: MsalException) {
                                deliver(callback, Result.failure(exception))
                            }
                        },
                    )
                },
                onFailure = { deliver(callback, Result.failure(it)) },
            )
        }
    }

    fun signIn(activity: Activity, callback: (Result<IAccount>) -> Unit) {
        initialize(activity) { appResult ->
            appResult.fold(
                onSuccess = { app ->
                    val parameters = SignInParameters.builder()
                        .withActivity(activity)
                        .withScopes(scopes)
                        .withCallback(
                            object : AuthenticationCallback {
                                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                                    deliver(
                                        callback,
                                        Result.success(authenticationResult.account),
                                    )
                                }

                                override fun onError(exception: MsalException) {
                                    deliver(callback, Result.failure(exception))
                                }

                                override fun onCancel() {
                                    deliver(
                                        callback,
                                        Result.failure(
                                            IllegalStateException(
                                                activity.getString(R.string.onedrive_login_cancelled),
                                            ),
                                        ),
                                    )
                                }
                            },
                        )
                        .build()
                    app.signIn(parameters)
                },
                onFailure = { deliver(callback, Result.failure(it)) },
            )
        }
    }

    fun acquireAccessToken(context: Context, callback: (Result<String>) -> Unit) {
        initialize(context) { appResult ->
            appResult.fold(
                onSuccess = { app ->
                    loadAccount(context) { accountResult ->
                        accountResult.fold(
                            onSuccess = { account ->
                                if (account == null) {
                                    deliver(
                                        callback,
                                        Result.failure(
                                            IllegalStateException(
                                                context.getString(R.string.onedrive_login_required),
                                            ),
                                        ),
                                    )
                                    return@fold
                                }
                                val parameters = AcquireTokenSilentParameters.Builder()
                                    .forAccount(account)
                                    .fromAuthority(account.authority)
                                    .withScopes(scopes)
                                    .build()
                                executor.execute {
                                    val tokenResult = runCatching {
                                        app.acquireTokenSilent(parameters).accessToken
                                    }.mapCatching { token ->
                                        token.takeIf(String::isNotBlank)
                                            ?: error(
                                                context.getString(
                                                    R.string.onedrive_token_failed,
                                                ),
                                            )
                                    }
                                    deliver(callback, tokenResult)
                                }
                            },
                            onFailure = { deliver(callback, Result.failure(it)) },
                        )
                    }
                },
                onFailure = { deliver(callback, Result.failure(it)) },
            )
        }
    }

    fun signOut(context: Context, callback: (Result<Unit>) -> Unit) {
        initialize(context) { appResult ->
            appResult.fold(
                onSuccess = { app ->
                    app.signOut(
                        object : ISingleAccountPublicClientApplication.SignOutCallback {
                            override fun onSignOut() {
                                deliver(callback, Result.success(Unit))
                            }

                            override fun onError(exception: MsalException) {
                                deliver(callback, Result.failure(exception))
                            }
                        },
                    )
                },
                onFailure = { deliver(callback, Result.failure(it)) },
            )
        }
    }

    private fun isConfigured(context: Context): Boolean =
        context.resources.openRawResource(R.raw.auth_config_single_account)
            .bufferedReader()
            .use { config ->
                val text = config.readText()
                "REPLACE_WITH_" !in text
            }

    private fun finishInitialization(
        result: Result<ISingleAccountPublicClientApplication>,
    ) {
        val callbacks = synchronized(lock) {
            if (result.isFailure) {
                initializationStarted = false
            }
            initializationCallbacks.toList().also {
                initializationCallbacks.clear()
            }
        }
        callbacks.forEach { deliver(it, result) }
    }

    private fun <T> deliver(callback: (Result<T>) -> Unit, result: Result<T>) {
        mainHandler.post { callback(result) }
    }
}
