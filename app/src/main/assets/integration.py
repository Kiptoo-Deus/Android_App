import re
import json
import os

def force_to_json(input_file, output_file):
    if not os.path.exists(input_file):
        print(f"❌ File not found: {input_file}")
        return

    with open(input_file, 'r', encoding='utf-8') as f:
        content = f.read()

    # 1. NEW STRATEGY: Find the '=' sign and take everything AFTER it.
    # This skips 'export const name: Type = '
    if '=' in content:
        data_part = content.split('=', 1)[1].strip()
    else:
        data_part = content

    # 2. Find the first '{' in that remaining data part
    match = re.search(r'\{.*\}', data_part, re.DOTALL)
    if not match:
        print(f"❌ Could not find the data object in {input_file}")
        return
    
    data_string = match.group(0)

    # 3. Clean up the syntax for JSON
    # Remove trailing semicolon if it exists
    if data_string.endswith(';'):
        data_string = data_string[:-1]

    # Replace single quotes with double quotes
    data_string = data_string.replace("'", '"')
    
    # Ensure keys are wrapped in double quotes (e.g., C: -> "C":)
    # This handles both numeric keys (10:) and string keys (C:)
    data_string = re.sub(r'(\w+):', r'"\1":', data_string)
    
    # Remove trailing commas before a closing bracket (e.g., [1, 2, ] -> [1, 2])
    data_string = re.sub(r',\s*([\}\]])', r'\1', data_string)

    try:
        parsed = json.loads(data_string)
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(parsed, f, indent=2)
        print(f"✅ Successfully converted {input_file} to {output_file}")
    except json.JSONDecodeError as e:
        print(f"❌ Still failed to parse {input_file}: {e}")
        print("Debugging - Start of string found:", data_string[:100])

# Fix your files
# MAKE SURE the .ts files are in the SAME folder as this script!
force_to_json('token_to_chord.ts', 'token_to_chord.json')
force_to_json('chord_to_notes.ts', 'chord_to_notes.json')