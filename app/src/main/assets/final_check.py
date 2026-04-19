import json
import onnxruntime as ort
import numpy as np

# 1. Load the real JSON we just converted
with open('token_to_chord.json', 'r', encoding='utf-8') as f:
    vocab = json.load(f)

# 2. Load the model
session = ort.InferenceSession("transformer_small.onnx")
input_name = session.get_inputs()[0].name

# 3. Simulate a sequence: User plays Chord ID "1" (B5)
# We pad to 256 as required by the model
test_input = np.zeros((1, 256), dtype=np.int64)
test_input[0, 0] = 1 

# 4. Run Inference
outputs = session.run(None, {input_name: test_input})
logits = outputs[0]

# 5. Get prediction for the next slot (index 1)
predicted_id = str(np.argmax(logits[0, 0, :]))

print(f"--- INTEGRATION VERIFIED ---")
print(f"Input: Chord ID 1 ({vocab['1']})")
print(f"Output: Predicted ID {predicted_id} ({vocab.get(predicted_id, 'Unknown')})")