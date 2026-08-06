package com.cofre.app;

import static com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialInterruptedException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.GetCredentialUnsupportedException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.webkit.WebViewAssetLoader;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cofre — invólucro Android com funcionamento local, sincronização Firebase
 * e login Google nativo através do Credential Manager.
 */
public class MainActivity extends Activity {

    private static final String TAG = "CofreAndroid";
    private static final int REQ_PICK = 1001;
    private static final int REQ_SAVE = 1002;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingBackup;

    private CredentialManager credentialManager;
    private MutableContextWrapper credentialContext;
    private final ExecutorService credentialExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean googleSignInInProgress = false;
    private String pendingGoogleMode = "login";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        credentialManager = CredentialManager.create(this);
        credentialContext = new MutableContextWrapper(this);

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(web);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equalsIgnoreCase(uri.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    Toast.makeText(MainActivity.this, "Não foi possível abrir o endereço", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(intent, "Escolher backup"), REQ_PICK);
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Não foi possível abrir os ficheiros", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new AndroidBridge(), "AndroidCofre");

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);

        if (savedInstanceState == null) {
            web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
        } else {
            web.restoreState(savedInstanceState);
        }
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public void saveBackup(final String filename, final String content) {
            runOnUiThread(() -> {
                pendingBackup = content;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, filename);
                try {
                    startActivityForResult(intent, REQ_SAVE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Não foi possível guardar", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void signInWithGoogle(final String mode) {
            try {
                runOnUiThread(() -> {
                    try {
                        startNativeGoogleSignIn(mode);
                    } catch (Throwable error) {
                        handleUnexpectedGoogleFailure("Não foi possível iniciar o login Google.", error);
                    }
                });
            } catch (Throwable error) {
                handleUnexpectedGoogleFailure("Não foi possível abrir o login Google.", error);
            }
        }

        @JavascriptInterface
        public void clearGoogleCredentialState() {
            clearNativeCredentialState();
        }
    }

    private void startNativeGoogleSignIn(String mode) {
        if (googleSignInInProgress) {
            notifyWebGoogleError("Já existe um login Google em andamento.");
            return;
        }
        googleSignInInProgress = true;
        pendingGoogleMode = "link".equals(mode) ? "link" : "login";

        try {
            String serverClientId = getString(R.string.default_web_client_id);
            if (serverClientId == null || serverClientId.trim().isEmpty()) {
                throw new IllegalStateException("default_web_client_id ausente");
            }

            // Este botão é uma acção explícita "Continuar com Google". A opção
            // específica para botões evita falhas conhecidas do GetGoogleIdOption
            // em alguns dispositivos Android 14+ com várias contas Google.
            GetSignInWithGoogleOption googleOption =
                    new GetSignInWithGoogleOption.Builder(serverClientId).build();

            GetCredentialRequest request = new GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build();

            // A documentação actual recomenda contexto baseado na Activity e
            // MutableContextWrapper para lançar correctamente a interface do sistema.
            credentialContext.setBaseContext(this);
            credentialManager.getCredentialAsync(
                    credentialContext,
                    request,
                    new CancellationSignal(),
                    credentialExecutor,
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse result) {
                            handleGoogleCredential(result.getCredential());
                        }

                        @Override
                        public void onError(@NonNull GetCredentialException error) {
                            googleSignInInProgress = false;
                            Log.w(TAG, "Falha no Credential Manager", error);
                            notifyWebGoogleError(credentialErrorMessage(error));
                        }
                    }
            );
        } catch (Throwable error) {
            handleUnexpectedGoogleFailure(
                    "O login Google não pôde ser aberto neste dispositivo.", error);
        }
    }

    private void handleUnexpectedGoogleFailure(String userMessage, Throwable error) {
        googleSignInInProgress = false;
        Log.e(TAG, "Falha inesperada no login Google nativo", error);
        notifyWebGoogleError(userMessage
                + " Actualize os Serviços do Google Play e tente novamente.");
    }

    private void handleGoogleCredential(Credential credential) {
        try {
            if (!(credential instanceof CustomCredential)
                    || !TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
                throw new IllegalStateException("Tipo de credencial Google inesperado");
            }
            CustomCredential customCredential = (CustomCredential) credential;
            GoogleIdTokenCredential googleCredential =
                    GoogleIdTokenCredential.createFrom(customCredential.getData());
            String idToken = googleCredential.getIdToken();
            googleSignInInProgress = false;
            notifyWebGoogleSuccess(idToken, pendingGoogleMode);
        } catch (Throwable e) {
            googleSignInInProgress = false;
            Log.e(TAG, "Não foi possível ler o token Google", e);
            notifyWebGoogleError("O Google devolveu uma credencial inválida. Actualize o APK e tente novamente.");
        }
    }

    private String credentialErrorMessage(GetCredentialException error) {
        if (error instanceof GetCredentialCancellationException
                || error instanceof GetCredentialInterruptedException) {
            return "O login Google foi cancelado.";
        }
        if (error instanceof NoCredentialException) {
            return "Não foi encontrada uma conta Google disponível neste dispositivo.";
        }
        if (error instanceof GetCredentialProviderConfigurationException) {
            return "O login Google não está configurado correctamente neste APK.";
        }
        if (error instanceof GetCredentialUnsupportedException) {
            return "Este dispositivo não suporta o selector Google actual. Actualize os Serviços do Google Play.";
        }
        String message = error.getLocalizedMessage();
        return message == null || message.trim().isEmpty()
                ? "Não foi possível concluir o login Google."
                : "Não foi possível concluir o login Google: " + message;
    }

    private void notifyWebGoogleSuccess(String idToken, String mode) {
        final String script = "window.CofreNativeGoogleAuth&&window.CofreNativeGoogleAuth.complete("
                + JSONObject.quote(idToken) + "," + JSONObject.quote(mode) + ");";
        runOnUiThread(() -> web.evaluateJavascript(script, null));
    }

    private void notifyWebGoogleError(String message) {
        final String script = "window.CofreNativeGoogleAuth&&window.CofreNativeGoogleAuth.fail("
                + JSONObject.quote(message) + ");";
        runOnUiThread(() -> web.evaluateJavascript(script, null));
    }

    private void clearNativeCredentialState() {
        try {
            credentialManager.clearCredentialStateAsync(
                    new ClearCredentialStateRequest(),
                    new CancellationSignal(),
                    credentialExecutor,
                    new CredentialManagerCallback<Void, ClearCredentialException>() {
                        @Override
                        public void onResult(Void result) {
                            Log.d(TAG, "Estado do Credential Manager limpo");
                        }

                        @Override
                        public void onError(@NonNull ClearCredentialException error) {
                            Log.w(TAG, "Não foi possível limpar o Credential Manager", error);
                        }
                    }
            );
        } catch (Throwable error) {
            Log.w(TAG, "Falha inesperada ao limpar o Credential Manager", error);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getDataString() != null) {
                    results = new Uri[]{Uri.parse(data.getDataString())};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
        } else if (requestCode == REQ_SAVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingBackup != null) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                    if (output == null) throw new IllegalStateException("Destino indisponível");
                    output.write(pendingBackup.getBytes("UTF-8"));
                    Toast.makeText(this, "Backup guardado com sucesso", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Erro ao guardar: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            pendingBackup = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        credentialExecutor.shutdownNow();
        if (web != null) {
            web.removeJavascriptInterface("AndroidCofre");
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
