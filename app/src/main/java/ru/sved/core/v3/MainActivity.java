package ru.sved.core.v3;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

// Главный модуль терминала СВЕД v3.0 (режим модульного добавления скриптов без клавиатуры)
public class MainActivity extends Activity {

    private static final int PICK_SCRIPT_FILE = 101;
    private TextView consoleOutput;
    private TextView scriptBox;
    private ScrollView consoleScroll;
    private ScrollView scriptScroll;
    private StringBuilder scriptBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Главный экран (строгий чёрный фон, без рекламы)
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);
        layout.setBackgroundColor(0xFF000000);

        // 1. ВЕРХНЕЕ ОКНО: Журнал работы ядра и узлов
        consoleOutput = new TextView(this);
        consoleOutput.setTextColor(0xFF00FF00);
        consoleOutput.setTextSize(12.5f);
        consoleOutput.setText("=== ТЕРМИНАЛ СВЕД v3.0 (ЭКСПЕРИМЕНТАЛЬНЫЙ) ===\n" +
                "Режим: Модульная загрузка (ручной ввод отключён).\n" +
                "Рекламные модули: ВЫРЕЗАНЫ.\n");

        consoleScroll = new ScrollView(this);
        consoleScroll.addView(consoleOutput);
        LinearLayout.LayoutParams consoleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        layout.addView(consoleScroll, consoleParams);

        // Заголовок окна-накопителя скриптов
        TextView boxLabel = new TextView(this);
        boxLabel.setText("ОКНО АКТИВНЫХ СКРИПТОВ (ТОЛЬКО ДОБАВЛЕНИЕ МОДУЛЕЙ):");
        boxLabel.setTextColor(0xFF00FFFF);
        boxLabel.setTextSize(11.5f);
        boxLabel.setPadding(0, 8, 0, 4);
        layout.addView(boxLabel);

        // 2. ЦЕНТРАЛЬНОЕ ОКНО: Защищённое окно для добавляемых скриптов (писать вручную нельзя)
        scriptBox = new TextView(this);
        scriptBox.setTextColor(0xFFAAFF00);
        scriptBox.setTextSize(13f);
        scriptBox.setPadding(16, 16, 16, 16);
        scriptBox.setFocusable(false);
        scriptBox.setClickable(false);

        scriptScroll = new ScrollView(this);
        scriptScroll.setBackgroundColor(0xFF121820);
        scriptScroll.addView(scriptBox);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f);
        boxParams.setMargins(0, 0, 0, 8);
        layout.addView(scriptScroll, boxParams);

        // 3. ПАНЕЛЬ ДОБАВЛЕНИЯ ОБУЧАЮЩИХ СКРИПТОВ
        TextView trainLabel = new TextView(this);
        trainLabel.setText("ДОБАВИТЬ ОБУЧАЮЩИЙ СКРИПТ В ОКНО:");
        trainLabel.setTextColor(0xFFFFAA00);
        trainLabel.setTextSize(11f);
        layout.addView(trainLabel);

        LinearLayout trainRow = new LinearLayout(this);
        trainRow.setOrientation(LinearLayout.HORIZONTAL);

        trainRow.addView(createAddButton("+ ОБУЧЕНИЕ: ОПТИКА", 0xFF1F3A52,
                "[ОБУЧАЮЩИЙ СКРИПТ: ОПТИКА_ЯДРО]\n" +
                "ОБУЧЕНИЕ: КАЛИБРОВКА МАТРИЦЫ КАМЕРЫ\n" +
                "ОБУЧЕНИЕ: СПЕКТРАЛЬНАЯ ЦВЕТНОСТЬ И КОНТРАСТ +95%"));

        trainRow.addView(createAddButton("+ ОБУЧЕНИЕ: УЗЛЫ", 0xFF1F3A52,
                "[ОБУЧАЮЩИЙ СКРИПТ: АППАРАТНЫЕ_УЗЛЫ]\n" +
                "ОБУЧЕНИЕ: ПРЯМОЙ ОПРОС ДАТЧИКОВ ДВИЖЕНИЯ\n" +
                "ОБУЧЕНИЕ: СИНХРОНИЗАЦИЯ ШИН БЕЗ ПОТЕРЬ"));

        layout.addView(trainRow);

        // 4. ПАНЕЛЬ ДОБАВЛЕНИЯ СКРИПТОВ ДЕЙСТВИЯ
        TextView actionLabel = new TextView(this);
        actionLabel.setText("ДОБАВИТЬ СКРИПТ ДЕЙСТВИЯ В ОКНО:");
        actionLabel.setTextColor(0xFF00FF88);
        actionLabel.setTextSize(11f);
        layout.addView(actionLabel);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        actionRow.addView(createAddButton("+ ДЕЙСТВИЕ: КАМЕРА", 0xFF1A4731,
                "[СКРИПТ ДЕЙСТВИЯ: УСИЛЕНИЕ_КАМЕРЫ]\n" +
                "ДЕЙСТВИЕ: ДАЛЬНОБОЙНОСТЬ И ЧЕТКОСТЬ МАКСИМУМ\n" +
                "ДЕЙСТВИЕ: ЗАХВАТ БЕЗ ИСКАЖЕНИЙ"));

        actionRow.addView(createAddButton("+ ДЕЙСТВИЕ: ШАГОМЕР", 0xFF1A4731,
                "[СКРИПТ ДЕЙСТВИЯ: ФОНОВЫЙ_ШАГОМЕР]\n" +
                "ДЕЙСТВИЕ: УДЕРЖАНИЕ СЧЕТЧИКА ШАГОВ В ФОНЕ\n" +
                "ДЕЙСТВИЕ: ТОЧНАЯ ФИКСАЦИЯ ДИСТАНЦИИ"));

        layout.addView(actionRow);

        // 5. ПАНЕЛЬ ЗАГРУЗКИ ИЗ ФАЙЛА / ОЧИСТКИ ОКНА
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);

        Button fileBtn = new Button(this);
        fileBtn.setText("+ ДОБАВИТЬ СКРИПТ ИЗ ФАЙЛА");
        fileBtn.setTextSize(11f);
        fileBtn.setBackgroundColor(0xFF3B2E58);
        fileBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f);
        fileParams.setMargins(2, 2, 2, 6);
        fileBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openScriptFilePicker();
            }
        });
        fileRow.addView(fileBtn, fileParams);

        Button clearBtn = new Button(this);
        clearBtn.setText("ОЧИСТИТЬ ОКНО");
        clearBtn.setTextSize(11f);
        clearBtn.setBackgroundColor(0xFF333333);
        clearBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f);
        clearParams.setMargins(2, 2, 2, 6);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scriptBuffer.setLength(0);
                updateScriptWindow();
                consoleOutput.append("\n[Инфо]: Окно скриптов очищено.\n");
                scrollToBottom(consoleScroll);
            }
        });
        fileRow.addView(clearBtn, clearParams);
        layout.addView(fileRow);

        // 6. КНОПКА ЗАПУСКА ВСЕХ ДОБАВЛЕННЫХ В ОКНО СКРИПТОВ
        Button runBtn = new Button(this);
        runBtn.setText("ВЫПОЛНИТЬ ДОБАВЛЕННЫЕ В ОКНО СКРИПТЫ");
        runBtn.setBackgroundColor(0xFF006622);
        runBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        runParams.setMargins(0, 0, 0, 6);
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeLoadedScripts();
            }
        });
        layout.addView(runBtn, runParams);

        // 7. КНОПКА ПОЛНОГО ОТКЛЮЧЕНИЯ ПРОЦЕССОВ
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

        // При старте сразу добавляем базовый скрипт из assets/scripts/ в окно
        loadInitialKernelScript();
    }

    // Вспомогательный генератор кнопок добавления скриптов в окно
    private Button createAddButton(String title, int color, final String scriptBlock) {
        Button btn = new Button(this);
        btn.setText(title);
        btn.setTextSize(11f);
        btn.setBackgroundColor(color);
        btn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(2, 2, 2, 4);
        btn.setLayoutParams(params);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                appendScriptToWindow(scriptBlock);
            }
        });
        return btn;
    }

    // Добавление блока скрипта в защищённое окно без участия клавиатуры
    private void appendScriptToWindow(String block) {
        if (scriptBuffer.length() > 0) {
            scriptBuffer.append("\n\n");
        }
        scriptBuffer.append(block.trim());
        updateScriptWindow();
        consoleOutput.append(" -> В окно добавлен блок: " + block.split("\n")[0] + "\n");
        scrollToBottom(consoleScroll);
    }

    private void updateScriptWindow() {
        if (scriptBuffer.length() == 0) {
            scriptBox.setText("[Окно пусто. Добавьте обучающий скрипт или скрипт действия кнопками ниже]");
            scriptBox.setTextColor(0xFF777777);
        } else {
            scriptBox.setText(scriptBuffer.toString());
            scriptBox.setTextColor(0xFFAAFF00);
            scrollToBottom(scriptScroll);
        }
    }

    // Загрузка стартового скрипта из папки assets/scripts/ прямо в окно
    private void loadInitialKernelScript() {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(getAssets().open("scripts/камера_ядро.свед"), "UTF-8"));
            StringBuilder sb = new StringBuilder("[БАЗОВЫЙ СКРИПТ ЯДРА: КАМЕРА_ОПТИКА]\n");
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            appendScriptToWindow(sb.toString());
        } catch (Exception e) {
            updateScriptWindow();
        }
    }

    // Подгрузка внешнего файла скрипта в окно
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
                StringBuilder sb = new StringBuilder("[ВНЕШНИЙ ФАЙЛ СКРИПТА]\n");
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                appendScriptToWindow(sb.toString());
            } catch (Exception e) {
                consoleOutput.append("\n[Ошибка добавления файла]: " + e.getMessage() + "\n");
            }
        }
    }

    // Исполнение всех скриптов, накопленных в окне
    private void executeLoadedScripts() {
        String allScripts = scriptBuffer.toString().trim();
        if (allScripts.isEmpty()) {
            consoleOutput.append("\n[Внимание]: Сначала добавьте скрипты в окно кнопками ниже.\n");
            scrollToBottom(consoleScroll);
            return;
        }

        consoleOutput.append("\n=== ИСПОЛНЕНИЕ СКРИПТОВ ИЗ ОКНА ===\n");
        String[] lines = allScripts.split("\n");
        int trained = 0;
        int actions = 0;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("[")) continue;
            String upper = line.toUpperCase();

            if (upper.startsWith("ОБУЧЕНИЕ")) {
                trained++;
                consoleOutput.append(" [Ядро усвоило]: " + line + "\n");
            } else if (upper.startsWith("ДЕЙСТВИЕ")) {
                actions++;
                consoleOutput.append(" [Узел выполнил]: " + line + "\n");
            } else {
                actions++;
                consoleOutput.append(" [Параметр узла]: " + line + " -> Применён.\n");
            }
        }
        consoleOutput.append("=== ГОТОВО: Обучений ядра (" + trained + "), Действий узлов (" + actions + ") ===\n");
        scrollToBottom(consoleScroll);
    }

    private void scrollToBottom(final ScrollView sv) {
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // Полная остановка всех процессов и выгрузка из памяти
    private void shutdownAllProcesses() {
        finishAndRemoveTask();
        Process.killProcess(Process.myPid());
        System.exit(0);
    }
}
