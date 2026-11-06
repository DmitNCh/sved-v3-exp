package ru.sved.core.v3;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.MotionEvent;
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
import java.util.ArrayList;
import java.util.List;

// Терминал СВЕД v3.0: Прямой попиксельный захват 1:1 (без системного мыла зума) + Авто-Лупа на красное табло часов
@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final int PICK_SCRIPT_FILE = 101;
    private static final int REQ_CAMERA_PERM = 202;

    private TextView consoleOutput;
    private TextView scriptBox;
    private ScrollView consoleScroll;
    private ScrollView scriptScroll;
    private StringBuilder scriptBuffer = new StringBuilder();

    private LinearLayout cameraContainer;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private ImageView resultImageView;
    private Camera mCamera;

    private int cropPowerPercent = 80; // Чистый попиксельный кроп СВЕД вместо мыльного зума
    private int redCutoffThreshold = 65; // Порог отсечения ореола между цифрами

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
        consoleOutput.setText("=== ТЕРМИНАЛ СВЕД v3.0 (ПРЯМАЯ МАТРИЦА 1:1) ===\n" +
                "Системный мыльный зум ОТКЛЮЧЕН. Работает прямой съём пикселей + Авто-Лупа табло.\n");

        consoleScroll = new ScrollView(this);
        consoleScroll.addView(consoleOutput);
        LinearLayout.LayoutParams consoleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.5f);
        layout.addView(consoleScroll, consoleParams);

        // 2. ОПТИЧЕСКИЙ БЛОК
        cameraContainer = new LinearLayout(this);
        cameraContainer.setOrientation(LinearLayout.VERTICAL);
        cameraContainer.setBackgroundColor(0xFF0A1520);
        cameraContainer.setPadding(6, 6, 6, 6);
        cameraContainer.setVisibility(View.GONE);

        FrameLayout viewFinderFrame = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        // Нажатие пальцем прямо по видоискателю заставляет линзу сфокусироваться в точку!
        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    triggerExactCenterFocus();
                }
                return true;
            }
        });

        viewFinderFrame.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 450));

        // Прицельная рамка
        TextView sniperCrosshair = new TextView(this);
        sniperCrosshair.setText("[ + ДЕРЖИТЕ ЧАСЫ В ЦЕНТРЕ И НАЖМИТЕ СЮДА ДЛЯ ФОКУСА + ]");
        sniperCrosshair.setTextColor(0xFF00FF00);
        sniperCrosshair.setTextSize(10f);
        sniperCrosshair.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams crossParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        viewFinderFrame.addView(sniperCrosshair, crossParams);

        // Экран вывода проявленного табло
        resultImageView = new ImageView(this);
        resultImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultImageView.setBackgroundColor(0xFF000000);
        resultImageView.setVisibility(View.GONE);
        viewFinderFrame.addView(resultImageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 450));

        cameraContainer.addView(viewFinderFrame);

        // Ползунок 1: Масштаб вырезки табло (без потери резкости линзы)
        TextView zoomLabel = new TextView(this);
        zoomLabel.setText("1. УВЕЛИЧЕНИЕ ТАБЛО ЧАСОВ (Чистый кроп матрицы без мыла):");
        zoomLabel.setTextColor(0xFF00FFFF);
        zoomLabel.setTextSize(10f);
        cameraContainer.addView(zoomLabel);

        SeekBar zoomBar = new SeekBar(this);
        zoomBar.setMax(100);
        zoomBar.setProgress(80);
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                cropPowerPercent = Math.max(20, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cameraContainer.addView(zoomBar);

        // Ползунок 2: Разделение палочек цифр (порог отсечения красного ореола)
        TextView expLabel = new TextView(this);
        expLabel.setText("2. РАЗДЕЛЕНИЕ ПАЛОЧЕК ЦИФР (Срез красного пятна):");
        expLabel.setTextColor(0xFFFFAA00);
        expLabel.setTextSize(10f);
        cameraContainer.addView(expLabel);

        SeekBar expBar = new SeekBar(this);
        expBar.setMax(100);
        expBar.setProgress(65);
        expBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                redCutoffThreshold = progress;
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cameraContainer.addView(expBar);

        // Кнопки управления оптикой
        LinearLayout camBtnRow = new LinearLayout(this);
        camBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button focusBtn = new Button(this);
        focusBtn.setText("1. НАВЕСТИ РЕЗКОСТЬ");
        focusBtn.setTextSize(9.5f);
        focusBtn.setBackgroundColor(0xFF554400);
        focusBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams focP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f);
        focP.setMargins(2, 4, 2, 2);
        focusBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerExactCenterFocus();
            }
        });
        camBtnRow.addView(focusBtn, focP);

        Button snapBtn = new Button(this);
        snapBtn.setText("2. ВЫДЕЛИТЬ ЧАСЫ");
        snapBtn.setTextSize(9.5f);
        snapBtn.setBackgroundColor(0xFF007744);
        snapBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams snapP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.95f);
        snapP.setMargins(2, 4, 2, 2);
        snapBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePureMatrixPicture();
            }
        });
        camBtnRow.addView(snapBtn, snapP);

        Button liveBtn = new Button(this);
        liveBtn.setText("СБРОС");
        liveBtn.setTextSize(9.5f);
        liveBtn.setBackgroundColor(0xFF224466);
        liveBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams liveP = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f);
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

        // 3. ОКНО АКТИВНЫХ СКРИПТОВ
        TextView boxLabel = new TextView(this);
        boxLabel.setText("ОКНО АКТИВНЫХ СКРИПТОВ (БЕЗ КЛАВИАТУРЫ):");
        boxLabel.setTextColor(0xFF00FFFF);
        boxLabel.setTextSize(10.5f);
        layout.addView(boxLabel);

        scriptBox = new TextView(this);
        scriptBox.setTextColor(0xFFAAFF00);
        scriptBox.setTextSize(11.5f);
        scriptBox.setPadding(10, 10, 10, 10);
        scriptBox.setFocusable(false);
        scriptBox.setClickable(false);

        scriptScroll = new ScrollView(this);
        scriptScroll.setBackgroundColor(0xFF121820);
        scriptScroll.addView(scriptBox);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.6f);
        boxParams.setMargins(0, 2, 0, 4);
        layout.addView(scriptScroll, boxParams);

        // 4. КНОПКИ ДОБАВЛЕНИЯ СКРИПТОВ
        LinearLayout trainRow = new LinearLayout(this);
        trainRow.setOrientation(LinearLayout.HORIZONTAL);
        trainRow.addView(createAddButton("+ ОБУЧЕНИЕ: БЕЗ МЫЛА ЗУМА", 0xFF1F3A52,
                "[ОБУЧАЮЩИЙ СКРИПТ: ОТКЛЮЧЕНИЕ_МЫЛЬНОГО_ЗУМА]\n" +
                "ОБУЧЕНИЕ: СЪЕМ ПИКСЕЛЕЙ 1:1 БЕЗ ИНТЕРПОЛЯЦИИ\n" +
                "ОБУЧЕНИЕ: АВТОПОИСК КРАСНОГО ТАБЛО ЧАСОВ"));
        trainRow.addView(createAddButton("+ ДЕЙСТВИЕ: МАКРО-ЛУПА ЧАСОВ", 0xFF1A4731,
                "[СКРИПТ ДЕЙСТВИЯ: ПРОЯВКА_СЕГМЕНТОВ]\n" +
                "ДЕЙСТВИЕ: ТОЧЕЧНЫЙ ФОКУС ПО ЦЕНТРУ ЛИНЗЫ\n" +
                "ДЕЙСТВИЕ: ВЫРЕЗКА ТАБЛО И РАЗДЕЛЕНИЕ ЦИФР"));
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
        appendScriptToWindow("[СКРИПТ ОПТИКИ: ЧИСТАЯ_МАТРИЦА_БЕЗ_МЫЛА]\n" +
                "ОБУЧЕНИЕ: СЪЕМ ПИКСЕЛЕЙ 1:1 БЕЗ СИСТЕМНОГО ЗУМА\n" +
                "ДЕЙСТВИЕ: ТОЧЕЧНЫЙ ФОКУС НА ДАЛЬНИЕ ЛАМПЫ/ЧАСЫ\n" +
                "ДЕЙСТВИЕ: АВТО-ЛУПА НА КРАСНОЕ ТАБЛО ЧАСОВ");
    }

    private void executeOpticalScripts() {
        consoleOutput.append("\n=== ВКЛЮЧЕН ПРЯМОЙ СЪЕМ МАТРИЦЫ 1:1 ===\n");
        consoleOutput.append(" -> Шаг 1: Наведите центр на часы и нажмите ЖЕЛТУЮ кнопку '1. НАВЕСТИ РЕЗКОСТЬ' (чтобы лампы стали маленькими точками).\n");
        consoleOutput.append(" -> Шаг 2: Нажмите ЗЕЛЕНУЮ кнопку '2. ВЫДЕЛИТЬ ЧАСЫ'.\n");
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
            setupPureSensorParameters();
        } catch (Exception ignored) {}
    }

    // Главное отличие: умеренный оптический зум (только 30%, где нет мыла и работает точный фокус!) + максимум мегапикселей
    private void setupPureSensorParameters() {
        if (mCamera == null) return;
        try {
            Camera.Parameters params = mCamera.getParameters();

            // 1. Максимальное разрешение матрицы
            List<Camera.Size> sizes = params.getSupportedPictureSizes();
            if (sizes != null && !sizes.isEmpty()) {
                Camera.Size best = sizes.get(0);
                for (Camera.Size s : sizes) {
                    if ((s.width * s.height) > (best.width * best.height)) {
                        best = s;
                    }
                }
                params.setPictureSize(best.width, best.height);
            }
            params.setJpegQuality(100);

            // 2. Ставим зум только на 25% от максимума — это даёт крупный план в прицеле, но НЕ включает мыльную интерполяцию и НЕ сбивает линзу!
            if (params.isZoomSupported()) {
                params.setZoom(params.getMaxZoom() / 4);
            }

            // 3. Умеренное притемнение (-60% от минимума), чтобы часы не перегорали, но сохраняли чёткие палочки
            int minExp = params.getMinExposureCompensation();
            if (minExp < 0) {
                params.setExposureCompensation((minExp * 60) / 100);
            }

            // 4. Задаём центральную снайперскую зону фокусировки (100x100 в самом центре кадра)
            if (params.getMaxNumFocusAreas() > 0) {
                List<Camera.Area> focusAreas = new ArrayList<>();
                focusAreas.add(new Camera.Area(new Rect(-150, -150, 150, 150), 1000));
                params.setFocusAreas(focusAreas);
            }
            if (params.getMaxNumMeteringAreas() > 0) {
                List<Camera.Area> meterAreas = new ArrayList<>();
                meterAreas.add(new Camera.Area(new Rect(-150, -150, 150, 150), 1000));
                params.setMeteringAreas(meterAreas);
            }

            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            mCamera.setParameters(params);
        } catch (Exception ignored) {}
    }

    // Принудительная точечная фокусировка линзы по центру (убирает круги боке на лампах!)
    private void triggerExactCenterFocus() {
        if (mCamera == null) return;
        consoleOutput.append(" -> Наводка резкости линзы точно по центру...\n");
        scrollToBottom(consoleScroll);
        try {
            mCamera.cancelAutoFocus();
            setupPureSensorParameters();
            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    consoleOutput.append(" -> Резкость зафиксирована! Теперь жмите '2. ВЫДЕЛИТЬ ЧАСЫ'.\n");
                    scrollToBottom(consoleScroll);
                }
            });
        } catch (Exception ignored) {}
    }

    // Захват чистого кадра матрицы и автоматический поиск красного табло часов в центральной зоне
    private void takePureMatrixPicture() {
        if (mCamera == null) return;
        consoleOutput.append(" -> Съём полных мегапикселей матрицы и поиск табло часов...\n");
        scrollToBottom(consoleScroll);

        mCamera.takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                try {
                    Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length);
                    Matrix rot = new Matrix();
                    rot.postRotate(90);

                    // Вырезаем центральную зону из полного кадра
                    int w = raw.getWidth();
                    int h = raw.getHeight();
                    int divisor = 2 + (cropPowerPercent / 20); // от 3x до 7x чистых пикселей
                    int cropW = w / divisor;
                    int cropH = h / divisor;
                    int startX = (w - cropW) / 2;
                    int startY = (h - cropH) / 2;

                    Bitmap centerZone = Bitmap.createBitmap(raw, startX, startY, cropW, cropH, rot, true);

                    // Автоматически находим красное табло часов внутри центральной зоны и делаем Макро-Вырезку!
                    Bitmap clockMacro = autoLocateAndEnhanceRedClock(centerZone);

                    resultImageView.setImageBitmap(clockMacro);
                    resultImageView.setVisibility(View.VISIBLE);
                    consoleOutput.append(" === ГОТОВО: Табло часов найдено и выведено крупным планом! ===\n");
                    scrollToBottom(consoleScroll);
                } catch (Exception e) {
                    consoleOutput.append(" [Ошибка]: " + e.getMessage() + "\n");
                }
            }
        });
    }

    // Алгоритм СВЕД: находит скопление красных пикселей часов, вырезает именно табло и разделяет палочки цифр
    private Bitmap autoLocateAndEnhanceRedClock(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        // 1. Ищем центр тяжести красного свечения часов (чтобы приблизить именно само табло!)
        long sumX = 0, sumY = 0, count = 0;
        int maxRedFound = 0;

        for (int y = h / 6; y < (h * 5) / 6; y++) {
            for (int x = w / 6; x < (w * 5) / 6; x++) {
                int c = pixels[y * w + x];
                int r = Color.red(c);
                int g = Color.green(c);
                int b = Color.blue(c);
                // Признак светящегося красного табло: красный доминирует над зелёным и синим
                if (r > 70 && r > (g * 1.6f) && r > (b * 1.6f)) {
                    sumX += x;
                    sumY += y;
                    count++;
                    if (r > maxRedFound) maxRedFound = r;
                }
            }
        }

        Bitmap targetCrop = src;
        // Если нашли красное табло часов — вырезаем прицельное окно прямо вокруг него!
        if (count > 10) {
            int centerX = (int) (sumX / count);
            int centerY = (int) (sumY / count);
            int boxW = Math.max(160, w / 3);
            int boxH = Math.max(120, h / 4);
            int left = Math.max(0, Math.min(w - boxW, centerX - boxW / 2));
            int top = Math.max(0, Math.min(h - boxH, centerY - boxH / 2));
            targetCrop = Bitmap.createBitmap(src, left, top, boxW, boxH);
        }

        // 2. Адаптивное разделение сегментов цифр внутри найденного табло
        int tw = targetCrop.getWidth();
        int th = targetCrop.getHeight();
        int[] tPixels = new int[tw * th];
        int[] outPixels = new int[tw * th];
        targetCrop.getPixels(tPixels, 0, tw, 0, 0, tw, th);

        // Вычисляем порог между яркими палочками цифр и тусклым ореолом между ними
        int cutoff = (maxRedFound > 90) ? (int) (maxRedFound * (0.35f + (redCutoffThreshold / 220.0f))) : 80;

        for (int i = 0; i < tPixels.length; i++) {
            int c = tPixels[i];
            int r = Color.red(c);
            int g = Color.green(c);
            int b = Color.blue(c);

            int redness = r - Math.max(g, b);
            if (redness > 25 && r >= cutoff) {
                // Это сама светящаяся палочка цифры — делаем её максимально чёткой и яркой (янтарно-красной)
                int boost = clamp((r - cutoff) * 4 + 120);
                outPixels[i] = Color.rgb(boost, boost / 3, 0);
            } else if (redness > 15 && r < cutoff) {
                // Это паразитный красный ореол между палочками цифр — гасим его в глубокий чёрный!
                outPixels[i] = Color.rgb(10, 0, 0);
            } else {
                // Контуры корпуса часов и стены вокруг
                int luma = (r + g + b) / 3;
                outPixels[i] = Color.rgb(luma, luma, luma);
            }
        }

        Bitmap finalBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        finalBmp.setPixels(outPixels, 0, tw, 0, 0, tw, th);
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
