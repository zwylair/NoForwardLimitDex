import sys
import zipfile
import subprocess
import shutil
import tempfile
import os
from pathlib import Path

def main():
    if len(sys.argv) < 2:
        print("Использование: python process_apk.py <путь_к_apk> [имя_итогового_jar]")
        sys.exit(1)

    apk_path = Path(sys.argv[1]).resolve()
    if not apk_path.exists():
        print(f"Ошибка: Файл '{apk_path}' не найден.")
        sys.exit(1)

    output_jar_name = sys.argv[2] if len(sys.argv) > 2 else "exteragram-stubs.jar"

    # Исправлено: работа через Path и автоматическое создание целевой директории
    output_dir = Path("./dex/libs").resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    output_jar_path = output_dir / output_jar_name

    # Определяем путь к скрипту dex2jar в локальной папке dex2jar
    dex2jar_dir = Path.cwd() / "dex2jar"
    is_windows = os.name == "nt"
    d2j_cmd_name = "d2j-dex2jar.bat" if is_windows else "d2j-dex2jar.sh"
    d2j_executable = dex2jar_dir / d2j_cmd_name

    if not d2j_executable.exists():
        d2j_executable = dex2jar_dir / "d2j-dex2jar"
        if not d2j_executable.exists():
            print(f"Ошибка: Исполняемый файл dex2jar не найден в '{dex2jar_dir}'.")
            sys.exit(1)

    with tempfile.TemporaryDirectory() as temp_dir:
        work_dir = Path(temp_dir)
        extracted_dex_dir = work_dir / "dex_files"
        extracted_dex_dir.mkdir()

        print(f"Распаковка .dex файлов из {apk_path.name}...")
        dex_files = []
        with zipfile.ZipFile(apk_path, 'r') as apk_zip:
            for item in apk_zip.namelist():
                if item.startswith("classes") and item.endswith(".dex") and "/" not in item:
                    apk_zip.extract(item, extracted_dex_dir)
                    dex_files.append(extracted_dex_dir / item)

        if not dex_files:
            print("Ошибка: Внутри APK не найдено ни одного .dex файла.")
            sys.exit(1)

        print(f"Найдено dex файлов: {len(dex_files)}")

        cmd = [str(d2j_executable)] + [str(f) for f in dex_files]
        print("Запуск dex2jar...")

        try:
            if not is_windows:
                os.chmod(d2j_executable, 0o755)

            result = subprocess.run(
                cmd,
                cwd=extracted_dex_dir,
                capture_output=True,
                text=True,
                shell=is_windows
            )

            if result.returncode != 0:
                print("Ошибка при выполнении dex2jar:")
                print(result.stderr or result.stdout)
                sys.exit(1)
        except Exception as e:
            print(f"Не удалось запустить dex2jar: {e}")
            sys.exit(1)

        generated_jars = list(extracted_dex_dir.glob("*.jar"))
        if not generated_jars:
            print("Ошибка: dex2jar не создал ни одного .jar файла.")
            sys.exit(1)

        print(f"Сгенерировано jar-файлов: {len(generated_jars)}. Распаковка и фильтрация...")

        combined_classes_dir = work_dir / "combined_classes"
        combined_classes_dir.mkdir()

        for jar in generated_jars:
            with zipfile.ZipFile(jar, 'r') as jar_zip:
                jar_zip.extractall(combined_classes_dir)

        # Очищаем META-INF
        meta_inf = combined_classes_dir / "META-INF"
        if meta_inf.exists():
            shutil.rmtree(meta_inf, ignore_errors=True)

        # Префиксы относительных путей для отфильтровывания (используем POSIX слэши)
        excluded_prefixes = (
            "android/",
            "androidx/",
            "kotlin/",
            "kotlinx/",
            "dev/exterahook/",
            "de/"
        )

        print(f"Упаковка с фильтрацией в файл: {output_jar_path.name}...")

        ignored_count = 0
        added_count = 0

        with zipfile.ZipFile(output_jar_path, 'w', compression=zipfile.ZIP_DEFLATED) as out_jar:
            for root, _, files in os.walk(combined_classes_dir):
                for file in files:
                    file_path = Path(root) / file
                    arcname = file_path.relative_to(combined_classes_dir)
                    arcname_posix = arcname.as_posix()

                    # Проверяем, не начинается ли путь с исключаемых пакетов
                    if any(arcname_posix.startswith(prefix) for prefix in excluded_prefixes):
                        ignored_count += 1
                        continue

                    out_jar.write(file_path, arcname)
                    added_count += 1

        print(f"Фильтрация завершена:")
        print(f" - Добавлено файлов: {added_count}")
        print(f" - Пропущено (исключено): {ignored_count}")
        print(f"\nГотово! Итоговый файл сохранён в:\n{output_jar_path}")

if __name__ == "__main__":
    main()
