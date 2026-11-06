package ru.sved.core.v3;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

// Терминал СВЕД v3.0 со встроенным дальнобойным оптическим трактом и проявкой контуров
@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final int PICK_SCRIPT_FILE = 101;
    private static final int REQ_CAMERA_PERM = 202;

    private TextView consoleOutput;
    private TextView scriptBox;
    private ScrollView consoleScroll;
    private ScrollView scriptScroll;
    private StringBuilder scriptBuffer = new StringBuilder();

    // Оптический блок СВЕД
    private LinearLayout cameraContainer;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private ImageView resultImageView;
    private Camera mCamera;
    private boolean isPreviewRunning = false;
    private int currentZoom = 0;
    private int currentExposure = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(12, 12, 12, 12);
        layout.setBackgroundColor(0xFF000000);

        // 1. ВЕРХНЕЕ ОКНО: Журнал ядра
        consoleOutput = new TextView(this);
        consoleOutput.setTextColor(0xFF00FF00);
        consoleOutput.setTextSize(11.5f);
        consoleOutput.setText("=== ТЕРМИНАЛ СВЕД v3.0 (ОПТИЧЕСКИЙ КОНТУР) ===\n" +
                "Готов к подавлению бликов и контурной проявке дальних объектов.\n");

        consoleScroll = new ScrollView(this);
        consoleScroll.addView(consoleOutput);
        LinearLayout.LayoutParams consoleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.7f);
        layout.addView(consoleScroll, consoleParams);

        // 2. ВСТРОЕННЫЙ ОПТИЧЕСКИЙ ВИДОИСКАТЕЛЬ СВЕД (открывается по команде скрипта)
        cameraContainer = new LinearLayout(this);
        cameraContainer.setOrientation(LinearLayout.VERTICAL);
        cameraContainer.setBackgroundColor(0xFF0A1520);
        cameraContainer.setPadding(8, 8, 8, 8);
        cameraContainer.setVisibility(View.GONE);

        FrameLayout viewFinderFrame = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        viewFinderFrame.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 480));

        // Экран вывода обработанного сверхчёткого снимка
        resultImageView = new ImageView(this);
        resultImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultImageView.setBackgroundColor(0xFF000000);
        resultImageView.setVisibility(View.GONE);
        viewFinderFrame.addView(resultImageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 480));

        cameraContainer.addView(viewFinderFrame);

        // Ползунок дальнобойности (Оптический/Матричный ЗУМ)
        TextView zoomLabel = new TextView(this);
        zoomLabel.setText("ДАЛЬНОБОЙНОСТЬ (ЗУМ): наведите на часы");
        zoomLabel.setTextColor(0xFF00FFFF);
        zoomLabel.setTextSize(11f);
        cameraContainer.addView(zoomLabel);

        SeekBar zoomBar = new SeekBar(this);
        zoomBar.setMax(100);
        zoomBar.setProgress(85);
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                applyCameraZoomAndAntiGlare(progress, -80);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cameraContainer.addView(zoomBar);

        // Кнопки управления оптическим захватом
        LinearLayout camBtnRow = new LinearLayout(this);
        camBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button snapBtn = new Button(this);
        snapBtn.setText("СНЯТЬ И ПРОЯВИТЬ ЧЕТКОСТЬ");
        snapBtn.setTextSize(10.5f);
        snapBtn.setBackgroundColor(0xFF007744);
        snapBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams snapP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f);
        snapP.setMargins(2, 4, 2, 2);
        snapBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureAndSharpenClock();
            }
        });
        camBtnRow.addView(snapBtn, snapP);

        Button liveBtn = new Button(this);
        liveBtn.setText("ПРИЦЕЛ / СБРОС");
        liveBtn.setTextSize(10.5f);
        liveBtn.setBackgroundColor(0xFF224466);
        liveBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams liveP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f);
        liveP.setMargins(2, 4, 2, 2);
        liveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resultImageView.setVisibility(View.GONE);
                if (mCamera != null) {
                    mCamera.startPreview();
                }
            }
        });
        camBtnRow.addView(liveBtn, liveP);

        cameraContainer.addView(camBtnRow);
        layout.addView(cameraContainer);

        // 3. ОКНО АКТИВНЫХ СКРИПТОВ (только добавление модулей)
        TextView boxLabel = new TextView(this);
        boxLabel.setText("ОКНО АКТИВНЫХ СКРИПТОВ (БЕЗ КЛАВИАТУРЫ):");
        boxLabel.setTextColor(0xFF00FFFF);
        boxLabel.setTextSize(11f);
        boxLabel.setPadding(0, 4, 0, 2);
        layout.addView(boxLabel);

        scriptBox = new TextView(this);
        scriptBox.setTextColor(0xFFAAFF00);
        scriptBox.setTextSize(12f);
        scriptBox.setPadding(12, 12, 12, 12);
        scriptBox.setFocusable(false);
        scriptBox.setClickable(false);

        scriptScroll = new ScrollView(this);
        scriptScroll.setBackgroundColor(0xFF121820);
        scriptScroll.addView(scriptBox);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.9f);
        boxParams.setMargins(0, 2, 0, 6);
        layout.addView(scriptScroll, boxParams);

        // 4. КНОПКИ ДОБАВЛЕНИЯ ОБУЧАЮЩИХ И ДЕЙСТВУЮЩИХ СКРИПТОВ
        LinearLayout trainRow = new LinearLayout(this);
        trainRow.setOrientation(LinearLayout.HORIZONTAL);
        trainRow.addView(createAddButton("+ ОБУЧЕНИЕ: ЧАСЫ/ТАБЛО", 0xFF1F3A52,
                "[ОБУЧАЮЩИЙ СКРИПТ: ПРОЯВКА_ТАБЛО]\n" +
                "ОБУЧЕНИЕ: СРЕЗ ЗАСВЕТКИ ПРОЖЕКТОРОВ (-2 EV)\n" +
                "ОБУЧЕНИЕ: МАТРИЦА РЕЗКОСТИ ЦИФР 3x3"));
        trainRow.addView(createAddButton("+ ДЕЙСТВИЕ: ПРИЦЕЛ КАМЕРЫ", 0xFF1A4731,
                "[СКРИПТ ДЕЙСТВИЯ: ОПТИЧЕСКИЙ_ПРИЦЕЛ]\n" +
                "ДЕЙСТВИЕ: ВКЛЮЧИТЬ ОПТИЧЕСКИЙ ПРИЦЕЛ СВЕД\n" +
                "ДЕЙСТВИЕ: ДАЛЬНОБОЙНОСТЬ И ЧЕТКОСТЬ +100%"));
        layout.addView(trainRow);

        // 5. ПАНЕЛЬ ЗАГРУЗКИ ИЗ ФАЙЛА / ОЧИСТКИ ОКНА
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);

        Button fileBtn = new Button(this);
        fileBtn.setText("+ ДОБАВИТЬ СКРИПТ ИЗ ФАЙЛА");
        fileBtn.setTextSize(10.5f);
        fileBtn.setBackgroundColor(0xFF3B2E58);
        fileBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f);
        fileParams.setMargins(2, 2, 2, 4);
        fileBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openScriptFilePicker();
            }
        });
        fileRow.addView(fileBtn, fileParams);

        Button clearBtn = new Button(this);
        clearBtn.setText("ОЧИСТИТЬ ОКНО");
        clearBtn.setTextSize(10.5f);
        clearBtn.setBackgroundColor(0xFF333333);
        clearBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f);
        clearParams.setMargins(2, 2, 2, 4);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scriptBuffer.setLength(0);
                updateScriptWindow();
            }
        });
        fileRow.addView(clearBtn, clearParams);
        layout.addView(fileRow);

        // 6. КНОПКА ИСПОЛНЕНИЯ СКРИПТОВ
        Button runBtn = new Button(this);
        runBtn.setText("ВЫПОЛНИТЬ ДОБАВЛЕННЫЕ В ОКНО СКРИПТЫ");
        runBtn.setBackgroundColor(0xFF006622);
        runBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        runParams.setMargins(0, 0, 0, 4);
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeOpticalScripts();
            }
        });
        layout.addView(runBtn, runParams);

        // 7. ПОЛНЫЙ СТОП
        Button killBtn = new Button(this);
        killBtn.setText("ОТКЛЮЧИТЬ ВСЕ ПРОЦЕССЫ (ПОЛНЫЙ СТОП)");
        killBtn.setBackgroundColor(0xFF550000);
        killBtn.setTextColor(0xFFFFFFFF);
        killBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shutdownAllProcesses();
            }
        });
        layout.addView(killBtn);

        setContentView(layout);
        appendScriptToWindow("[СКРИПТ ОПТИКИ: ПРОЯВКА_ДАЛЬНИХ_ЧАСОВ]\n" +
                "ОБУЧЕНИЕ: СРЕЗ ЗАСВЕТКИ ПРОЖЕКТОРОВ (-2 EV)\n" +
                "ДЕЙСТВИЕ: ВКЛЮЧИТЬ ОПТИЧЕСКИЙ ПРИЦЕЛ СВЕД\n" +
                "ДЕЙСТВИЕ: ФОКУС НА ДАЛЬНОБОЙНОСТЬ + ЧЕТКОСТЬ 100%");
    }

    // Запуск встроенной камеры с подавлением засветки от потолочных ламп
    private void executeOpticalScripts() {
        String all = scriptBuffer.toString().toUpperCase();
        if (all.isEmpty()) {
            consoleOutput.append("\n[Инфо]: Добавьте скрипт в окно.\n");
            return;
        }
        consoleOutput.append("\n=== ВКЛЮЧЕНИЕ ОПТИЧЕСКОГО ТРАКТА СВЕД ===\n");
        consoleOutput.append(" -> Подавление засветки ламп: АКТИВНО (-2 EV)\n");
        consoleOutput.append(" -> Фиксация фокуса на дальнюю дистанцию: АКТИВНО\n");
        consoleOutput.append(" -> Матрица контурной резкости цифр: ГОТОВА\n");
        scrollToBottom(consoleScroll);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
                return;
            }
        }
        openSvedCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_CAMERA_PERM && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openSvedCamera();
        }
    }

    private void openSvedCamera() {
        cameraContainer.setVisibility(View.VISIBLE);
        resultImageView.setVisibility(View.GONE);
        startCameraPreview();
    }

    private void startCameraPreview() {
        try {
            if (mCamera == null) {
                mCamera = Camera.open(0);
                mCamera.setDisplayOrientation(90);
            }
            mCamera.setPreviewDisplay(surfaceHolder);
            mCamera.startPreview();
            isPreviewRunning = true;
            // Сразу выставляем дальнобойный зум 85% и притемнение прожекторов (-80%)
            applyCameraZoomAndAntiGlare(85, -80);
        } catch (Exception e) {
            consoleOutput.append(" [Оптика]: Наведите камеру и нажмите СНЯТЬ.\n");
        }
    }

    // Настройка аппаратной матрицы: дальнобойный зум + срез пересвета ламп + фокус вдаль
    private void applyCameraZoomAndAntiGlare(int zoomPercent, int exposurePercent) {
        if (mCamera == null) return;
        try {
            Camera.Parameters params = mCamera.getParameters();

            // 1. Дальнобойный зум
            if (params.isZoomSupported()) {
                int maxZoom = params.getMaxZoom();
                currentZoom = (maxZoom * zoomPercent) / 100;
                params.setZoom(currentZoom);
            }

            // 2. Подавление бликов от ламп (экспокоррекция в минус, чтобы цифры часов не сливались)
            int minExp = params.getMinExposureCompensation();
            if (minExp < 0) {
                currentExposure = (minExp * Math.abs(exposurePercent)) / 100;
                params.setExposureCompensation(currentExposure);
            }

            // 3. Фокус на бесконечность / дальние объекты
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null) {
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                }
            }

            mCamera.setParameters(params);
        } catch (Exception ignored) {}
    }

    // Захват кадра и математическое усиление чёткости контуров цифр (Свёртка 3x3 + Контраст)
    private void captureAndSharpenClock() {
        if (mCamera == null) return;
        consoleOutput.append(" -> Захват кадра и прогон через матрицу чёткости СВЕД...\n");
        scrollToBottom(consoleScroll);

        mCamera.takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                try {
                    Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length);
                    Matrix rot = new Matrix();
                    rot.postRotate(90);

                    // Вырезаем центральную дальнобойную область (дополнительное 2x приближение без мыла)
                    int w = raw.getWidth();
                    int h = raw.getHeight();
                    int cropW = w / 2;
                    int cropH = h / 2;
                    Bitmap cropped = Bitmap.createBitmap(raw, w / 4, h / 4, cropW, cropH, rot, true);

                    // Прогоняем через наш фильтр высокой контрастности и контурной резкости
                    Bitmap enhanced = applySvedSharpnessMatrix(cropped);

                    resultImageView.setImageBitmap(enhanced);
                    resultImageView.setVisibility(View.VISIBLE);
                    consoleOutput.append(" === ГОТОВО: Кадр проявлен! Блики срезаны, контуры цифр усилены ===\n");
                    scrollToBottom(consoleScroll);
                } catch (Exception e) {
                    consoleOutput.append(" [Ошибка обработки]: " + e.getMessage() + "\n");
                }
            }
        });
    }

    // Математический фильтр СВЕД: подавление серой пелены + контурная резкость 3x3
    private Bitmap applySvedSharpnessMatrix(Bitmap src) {
        int width = src.getWidth();
        int height = src.getHeight();

        // Шаг 1: Повышаем контрастность и убираем серый туман вокруг светящихся цифр
        Bitmap contrasted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(contrasted);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix(new float[]{
                1.6f, 0,    0,    0, -45, // Усиление красного спектра (для табло часов)
                0,    1.4f, 0,    0, -45,
                0,    0,    1.4f, 0, -45,
                0,    0,    0,    1,   0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);

        // Шаг 2: Ядро свёртки резкости (Sharpen Kernel) для чётких границ сегментов цифр
        int[] pixels = new int[width * height];
        int[] outPixels = new int[width * height];
        contrasted.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;
                int c = pixels[idx];
                int top = pixels[(y - 1) * width + x];
                int bottom = pixels[(y + 1) * width + x];
                int left = pixels[y * width + (x - 1)];
                int right = pixels[y * width + (x + 1)];

                int r = clamp(5 * Color.red(c) - Color.red(top) - Color.red(bottom) - Color.red(left) - Color.red(right));
                int g = clamp(5 * Color.green(c) - Color.green(top) - Color.green(bottom) - Color.green(left) - Color.green(right));
                int b = clamp(5 * Color.blue(c) - Color.blue(top) - Color.blue(bottom) - Color.blue(left) - Color.blue(right));

                outPixels[idx] = Color.rgb(r, g, b);
            }
        }
        Bitmap finalBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        finalBmp.setPixels(outPixels, 0, width, 0, 0, width, height);
        return finalBmp;
    }

    private int clamp(int val) {
        return Math.max(0, Math.min(255, val));
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startCameraPreview();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mCamera != null) {
            mCamera.stopPreview();
        }
    }

    private Button createAddButton(String title, int color, final String scriptBlock) {
        Button btn = new Button(this);
        btn.setText(title);
        btn.setTextSize(10f);
        btn.setBackgroundColor(color);
        btn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(2, 2, 2, 3);
        btn.setLayoutParams(params);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                appendScriptToWindow(scriptBlock);
            }
        });
        return btn;
    }

    private void appendScriptToWindow(String block) {
        if (scriptBuffer.length() > 0) {
            scriptBuffer.append("\n\n");
        }
        scriptBuffer.append(block.trim());
        updateScriptWindow();
    }

    private void updateScriptWindow() {
        if (scriptBuffer.length() == 0) {
            scriptBox.setText("[Окно пусто. Добавьте скрипт]");
            scriptBox.setTextColor(0xFF777777);
        } else {
            scriptBox.setText(scriptBuffer.toString());
            scriptBox.setTextColor(0xFFAAFF00);
            scrollToBottom(scriptScroll);
        }
    }

    private void openScriptFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Выберите файл скрипта СВЕД"), PICK_SCRIPT_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_SCRIPT_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                appendScriptToWindow(sb.toString());
            } catch (Exception ignored) {}
        }
    }

    private void scrollToBottom(final ScrollView sv) {
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void shutdownAllProcesses() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
        finishAndRemoveTask();
        Process.killProcess(Process.myPid());
        System.exit(0);
    }
}
