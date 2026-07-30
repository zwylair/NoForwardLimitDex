import base64
import sys
import zlib


def main():
    if len(sys.argv) < 2:
        print("Использование: python compress.py <путь_к_файлу>")
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = f"{input_file}.compressed.txt"

    # 1. Читаем исходный файл в бинарном режиме
    try:
        with open(input_file, "rb") as f:
            data = f.read()
    except FileNotFoundError:
        print(f"Ошибка: Файл '{input_file}' не найден.")
        sys.exit(1)

    # 2. Максимальное сжатие zlib (уровень 9)
    compressed_data = zlib.compress(data, level=zlib.Z_BEST_COMPRESSION)

    # 3. Кодирование в Base64
    b64_data = base64.b64encode(compressed_data).decode("utf-8")

    # 4. Сохранение в текстовый файл
    with open(output_file, "w", encoding="utf-8") as f:
        f.write(b64_data)

    print(f"Готово! Файл сохранен в: {output_file}")
    print(f"Исходный размер: {len(data)} байт")
    print(f"Сжатый Base64 размер: {len(b64_data)} байт")


if __name__ == "__main__":
    main()
