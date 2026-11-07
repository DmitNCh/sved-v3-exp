package ru.sved.core.v3;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
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
import java.util.Collections;

// Терминал СВЕД v3.0: Прямой аппаратный затвор Camera2 API (Сверхкороткая выдержка против выгорания цифр + Ручной фокус линзы)
public class MainActivity extends Activity {

    private static final int PICK_SCRIPT_FILE = 101;
    private static final int REQ_CAMERA_PERM = 202;

    private TextView consoleOutput;
    private TextView scriptBox;
    private ScrollView consoleScroll;
    private ScrollView scriptScroll;
    private StringBuilder scriptBuffer = new StringBuilder();

    private LinearLayout cameraContainer;
    private TextureView textureView;
    private ImageView resultImageView;

    // Низкоуровневое управление матрицей Camera2
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private String cameraId;

    // Параметры против выгорания светодиодных часов
    private long shutterSpeedNs = 1500000L; // 1/660 секунды по умолчанию (срезает оранжевое пятно!)
    private float focusDiopters = 0.0f;     // 0.0 = строгая бесконечность (дальняя стена цеха)
    private int sniperZoomFactor = 7;       // 7-кратное увеличение центральной зоны

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(10, 10, 10, 10);
        layout.setBackgroundColor(0xFF000000);

        // 1. ВЕРХНЕЕ ОКНО: Журнал ядра
        consoleOutput = new TextView(this);
        consoleOutput.setTextColor(0xFF00FF00);
        consoleOutput.setTextSize(11f);
        consoleOutput.setText("=== ТЕРМИНАЛ СВЕД v3.0 (ПРЯМОЙ ЗАТВОР CAMERA2) ===\n" +
                "Режим: Ручная сверхкороткая выдержка (против слияния цифр в пятно) + Ручной фокус.\n");

        consoleScroll = new ScrollView(this);
        consoleScroll.addView(consoleOutput);
        LinearLayout.LayoutParams consoleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.45f);
        layout.addView(consoleScroll, consoleParams);

        // 2. ОПТИЧЕСКИЙ БЛОК С ЖИВЫМ КОНТРОЛЕМ ВЫДЕРЖКИ И ЛИНЗЫ
        cameraContainer = new LinearLayout(this);
        cameraContainer.setOrientation(LinearLayout.VERTICAL);
        cameraContainer.setBackgroundColor(0xFF0A1520);
        cameraContainer.setPadding(6, 6, 6, 6);
        cameraContainer.setVisibility(View.GONE);

        FrameLayout viewFinderFrame = new FrameLayout(this);
        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(surfaceTextureListener);
        viewFinderFrame.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 440));

        // Прицельный крест по центру
        TextView sniperCrosshair = new TextView(this);
        sniperCrosshair.setText("[ + ДЕРЖИТЕ ЧАСЫ В ЦЕНТРЕ + ]");
        sniperCrosshair.setTextColor(0xFF00FF00);
        sniperCrosshair.setTextSize(11f);
        sniperCrosshair.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams crossParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        viewFinderFrame.addView(sniperCrosshair, crossParams);

        // Окно увеличенного кадра часов
        resultImageView = new ImageView(this);
        resultImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultImageView.setBackgroundColor(0xFF000000);
        resultImageView.setVisibility(View.GONE);
        viewFinderFrame.addView(resultImageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 440));

        cameraContainer.addView(viewFinderFrame);

        // Ползунок 1: СКОРОСТЬ ЗАТВОРА (убирает оранжевое пятно прямо вживую на экране!)
        final TextView shutterLabel = new TextView(this);
        shutterLabel.setText("1. СКОРОСТЬ ЗАТВОРА (двигайте вправо, пока пятно не распадётся на цифры):");
        shutterLabel.setTextColor(0xFFFFAA00);
        shutterLabel.setTextSize(10f);
        cameraContainer.addView(shutterLabel);

        SeekBar shutterBar = new SeekBar(this);
        shutterBar.setMax(100);
        shutterBar.setProgress(75);
        shutterBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // От 1/60 сек (слева) до сверхкороткой 1/8000 сек (справа)
                double ratio = (100 - progress) / 100.0;
                shutterSpeedNs = (long) (125000L + (ratio * ratio * 16000000L));
                int frac = (int) (1000000000L / Math.max(1, shutterSpeedNs));
                shutterLabel.setText("1. ВЫДЕРЖКА ЗАТВОРА: 1/" + frac + " сек (меньше пятно -> чётче цифры)");
                updateLiveCamera2Parameters();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cameraContainer.addView(shutterBar);

        // Ползунок 2: РУЧНОЙ ФОКУС ЛИНЗЫ (чтобы часы и лампы никогда не расплывались в круги!)
        final TextView focusLabel = new TextView(this);
        focusLabel.setText("2. РУЧНОЙ ФОКУС ЛИНЗЫ (0.0 слева = строгая даль, круги сожмутся в точки):");
        focusLabel.setTextColor(0xFF00FFFF);
        focusLabel.setTextSize(10f);
        cameraContainer.addView(focusLabel);

        SeekBar focusBar = new SeekBar(this);
        focusBar.setMax(100);
        focusBar.setProgress(5); // Почти на самом упоре бесконечности
        focusBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // От 0.0 диоптрий (бесконечность) до 3.0 диоптрий
                focusDiopters = (progress / 100.0f) * 3.0f;
                focusLabel.setText(String.format("2. РУЧНОЙ ФОКУС ЛИНЗЫ: %.2f диоптр. (ищите самые острые точки ламп)", focusDiopters));
                updateLiveCamera2Parameters();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cameraContainer.addView(focusBar);

        // Кнопки захвата и возврата к прицелу
        LinearLayout camBtnRow = new LinearLayout(this);
        camBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button snapBtn = new Button(this);
        snapBtn.setText("ЗАХВАТИТЬ И УВЕЛИЧИТЬ ЦЕНТР (8X)");
        snapBtn.setTextSize(10f);
        snapBtn.setBackgroundColor(0xFF007744);
        snapBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams snapP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f);
        snapP.setMargins(2, 4, 2, 2);
        snapBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureSniperTextureCrop();
            }
        });
        camBtnRow.addView(snapBtn, snapP);

        Button liveBtn = new Button(this);
        liveBtn.setText("ЖИВОЙ ПРИЦЕЛ");
        liveBtn.setTextSize(10f);
        liveBtn.setBackgroundColor(0xFF224466);
        liveBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams liveP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.65f);
        liveP.setMargins(2, 4, 2, 2);
        liveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resultImageView.setVisibility(View.GONE);
            }
        });
        camBtnRow.addView(liveBtn, liveP);

        cameraContainer.addView(camBtnRow);
        layout.addView(cameraContainer);

        // 3. ОКНО АКТИВНЫХ СКРИПТОВ
        TextView boxLabel = new TextView(this);
        boxLabel.setText("ОКНО АКТИВНЫХ СКРИПТОВ (БЕЗ КЛАВИАТУРЫ):");
        boxLabel.setTextColor(0xFF00FFFF);
        boxLabel.setTextSize(10.5f);
        layout.addView(boxLabel);

        scriptBox = new TextView(this);
        scriptBox.setTextColor(0xFFAAFF00);
        scriptBox.setTextSize(11f);
        scriptBox.setPadding(10, 10, 10, 10);
        scriptBox.setFocusable(false);
        scriptBox.setClickable(false);

        scriptScroll = new ScrollView(this);
        scriptScroll.setBackgroundColor(0xFF121820);
        scriptScroll.addView(scriptBox);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.55f);
        boxParams.setMargins(0, 2, 0, 4);
        layout.addView(scriptScroll, boxParams);

        // 4. КНОПКИ ДОБАВЛЕНИЯ СКРИПТОВ
        LinearLayout trainRow = new LinearLayout(this);
        trainRow.setOrientation(LinearLayout.HORIZONTAL);
        trainRow.addView(createAddButton("+ ОБУЧЕНИЕ: ЗАТВОР 1/2000", 0xFF1F3A52,
                "[ОБУЧАЮЩИЙ СКРИПТ: ЗАТВОР_ПРОТИВ_ПЯТНА]\n" +
                "ОБУЧЕНИЕ: ОТКЛЮЧЕНИЕ АВТОЭКСПОЗИЦИИ ANDROID\n" +
                "ОБУЧЕНИЕ: ВЫДЕРЖКА 1/2000 СЕК + ISO 100"));
        trainRow.addView(createAddButton("+ ДЕЙСТВИЕ: РУЧНАЯ ЛИНЗА", 0xFF1A4731,
                "[СКРИПТ ДЕЙСТВИЯ: ЖЕСТКИЙ_ФОКУС_ДАЛЬ]\n" +
                "ДЕЙСТВИЕ: БЛОКИРОВКА АВТОФОКУСА НА 0.0 ДИОПТРИЙ\n" +
                "ДЕЙСТВИЕ: КОНТУРНАЯ ПРОЯВКА СЕГМЕНТОВ"));
        layout.addView(trainRow);

        // 5. ФАЙЛ / ОЧИСТКА
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);

        Button fileBtn = new Button(this);
        fileBtn.setText("+ ДОБАВИТЬ СКРИПТ ИЗ ФАЙЛА");
        fileBtn.setTextSize(10f);
        fileBtn.setBackgroundColor(0xFF3B2E58);
        fileBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f);
        fileParams.setMargins(2, 2, 2, 3);
        fileBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openScriptFilePicker();
            }
        });
        fileRow.addView(fileBtn, fileParams);

        Button clearBtn = new Button(this);
        clearBtn.setText("ОЧИСТИТЬ ОКНО");
        clearBtn.setTextSize(10f);
        clearBtn.setBackgroundColor(0xFF333333);
        clearBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f);
        clearParams.setMargins(2, 2, 2, 3);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scriptBuffer.setLength(0);
                updateScriptWindow();
            }
        });
        fileRow.addView(clearBtn, clearParams);
        layout.addView(fileRow);

        // 6. ЗАПУСК СКРИПТОВ
        Button runBtn = new Button(this);
        runBtn.setText("ВЫПОЛНИТЬ ДОБАВЛЕННЫЕ В ОКНО СКРИПТЫ");
        runBtn.setBackgroundColor(0xFF006622);
        runBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        runParams.setMargins(0, 0, 0, 3);
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeCamera2Scripts();
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
        appendScriptToWindow("[СКРИПТ ОПТИКИ: РУЧНОЙ_ЗАТВОР_CAMERA2]\n" +
                "ОБУЧЕНИЕ: СВЕРХКОРОТКАЯ ВЫДЕРЖКА ПРОТИВ ВЫГОРАНИЯ ЦИФР\n" +
                "ДЕЙСТВИЕ: РУЧНАЯ ФИКСАЦИЯ ЛИНЗЫ НА БЕСКОНЕЧНОСТЬ (0.0)\n" +
                "ДЕЙСТВИЕ: КОНТУРНАЯ ПРОЯВКА СВЕТОДИОДОВ");
    }

    private void executeCamera2Scripts() {
        consoleOutput.append("\n=== ЗАПУСК РУЧНОГО ЗАТВОРА CAMERA2 API ===\n");
        consoleOutput.append(" -> Ползунок 1 регулирует скорость затвора (убирает оранжевый пересвет).\n");
        consoleOutput.append(" -> Ползунок 2 жёстко держит линзу на бесконечности (никаких пушистых кругов!).\n");
        scrollToBottom(consoleScroll);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
                return;
            }
        }
        cameraContainer.setVisibility(View.VISIBLE);
        resultImageView.setVisibility(View.GONE);
        if (textureView.isAvailable()) {
            openCamera2Hardware();
        }
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera2Hardware();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return false; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };

    private void openCamera2Hardware() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            cameraId = manager.getCameraIdList()[0];

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startCamera2PreviewSession();
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }
                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                }
            }, null);
        } catch (Exception e) {
            consoleOutput.append(" [Ошибка Camera2]: " + e.getMessage() + "\n");
        }
    }

    private void startCamera2PreviewSession() {
        try {
            if (cameraDevice == null || !textureView.isAvailable()) return;
            SurfaceTexture texture = textureView.getSurfaceTexture();
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            // Выбираем самое чёткое разрешение видоискателя (1920x1080 или выше)
            Size bestSize = new Size(1920, 1080);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                if (sizes != null && sizes.length > 0) {
                    bestSize = sizes[0];
                    for (Size s : sizes) {
                        if (s.getWidth() * s.getHeight() > bestSize.getWidth() * bestSize.getHeight()
                                && s.getWidth() <= 3840) {
                            bestSize = s;
                        }
                    }
                }
            }
            texture.setDefaultBufferSize(bestSize.getWidth(), bestSize.getHeight());
            Surface surface = new Surface(texture);

            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            updateLiveCamera2Parameters();
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {}
                    }, null);
        } catch (Exception ignored) {}
    }

    // Прямая подача короткой выдержки, минимального ISO и ручного фокуса линзы в железо смартфона
    private void updateLiveCamera2Parameters() {
        if (cameraDevice == null || captureSession == null || previewBuilder == null) return;
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);

            // 1. Отключаем автофокус, который делал пушистые круги, и жёстко фиксируем линзу на дальность!
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            previewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters);

            // 2. Пробуем включить полностью ручной затвор (CONTROL_AE_MODE_OFF) со сверхкороткой выдержкой и ISO 100
            Range<Long> expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Range<Integer> isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

            if (expRange != null && isoRange != null) {
                previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                long clampedExp = Math.max(expRange.getLower(), Math.min(expRange.getUpper(), shutterSpeedNs));
                int minIso = Math.max(50, isoRange.getLower());
                previewBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedExp);
                previewBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, minIso);
            } else {
                // Если телефон блокирует ручной затвор, ставим максимальное притемнение AE
                previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                Range<Integer> compRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (compRange != null) {
                    previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compRange.getLower());
                }
            }

            // 3. Включаем максимальную аппаратную контурную резкость процессора камеры
            previewBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);

            captureSession.setRepeatingRequest(previewBuilder.build(), null, null);
        } catch (CameraAccessException ignored) {}
    }

    // Захват идеально сфокусированного центра прямо с матричной текстуры (8x увеличение без потери резкости)
    private void captureSniperTextureCrop() {
        Bitmap fullFrame = textureView.getBitmap();
        if (fullFrame == null) return;

        int w = fullFrame.getWidth();
        int h = fullFrame.getHeight();
        int cropW = w / sniperZoomFactor;
        int cropH = h / sniperZoomFactor;
        int startX = (w - cropW) / 2;
        int startY = (h - cropH) / 2;

        Bitmap centerCrop = Bitmap.createBitmap(fullFrame, startX, startY, cropW, cropH);
        Bitmap sharpened = sharpenClockSegments(centerCrop);

        resultImageView.setImageBitmap(sharpened);
        resultImageView.setVisibility(View.VISIBLE);
        consoleOutput.append(" === ГОТОВО: Захвачен центр при выдержке 1/" +
                (1000000000L / Math.max(1, shutterSpeedNs)) + " сек и фокусе " +
                String.format("%.2f", focusDiopters) + " ===\n");
        scrollToBottom(consoleScroll);
    }

    // Мягкое усиление чёткости границ цифр без заливки их в сплошное пятно
    private Bitmap sharpenClockSegments(Bitmap src) {
        int width = src.getWidth();
        int height = src.getHeight();

        Bitmap contrasted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(contrasted);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix(new float[]{
                1.35f, 0,     0,     0, -25,
                0,     1.35f, 0,     0, -25,
                0,     0,     1.35f, 0, -25,
                0,     0,     0,     1,   0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);

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

    private Button createAddButton(String title, int color, final String scriptBlock) {
        Button btn = new Button(this);
        btn.setText(title);
        btn.setTextSize(9.5f);
        btn.setBackgroundColor(color);
        btn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(2, 2, 2, 2);
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
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        finishAndRemoveTask();
        Process.killProcess(Process.myPid());
        System.exit(0);
    }
}
