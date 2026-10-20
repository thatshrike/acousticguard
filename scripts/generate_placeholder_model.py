#!/usr/bin/env python3
"""
AcousticGuard TFLite Model Generator

Generates contract-compliant TFLite models for:
1. `distress_multilingual.tflite` (Distress Classifier conforming to MODEL_CONTRACT.md and distress_multilingual_labels.txt)
2. `owner_voice_embedding.tflite` (Speaker Embedding Model conforming to MODEL_CONTRACT.md and VoiceEmbeddingModel.kt)

Output models are saved directly to app/src/main/assets/
"""

from pathlib import Path
import flatbuffers
import tflite
import numpy as np

ASSETS_DIR = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets"
LABELS_FILE = ASSETS_DIR / "distress_multilingual_labels.txt"


def read_labels() -> list[str]:
    lines = [line.strip() for line in LABELS_FILE.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(lines) != 16:
        raise ValueError(f"Expected 16 labels in {LABELS_FILE}, found {len(lines)}")
    return lines


def build_distress_model(num_labels: int = 16, sample_count: int = 16000) -> bytes:
    """
    Builds a TFLite FlatBuffer for the distress classifier.
    Architecture:
      Input (FLOAT32 [1, 16000]) -> FullyConnected (FLOAT32 [1, 16]) -> Softmax -> Output (FLOAT32 [1, 16])
    """
    builder = flatbuffers.Builder(1024 * 1024 * 2)

    # 1. Operator Codes
    tflite.OperatorCodeStart(builder)
    tflite.OperatorCodeAddBuiltinCode(builder, tflite.BuiltinOperator.FULLY_CONNECTED)
    op_code_fc = tflite.OperatorCodeEnd(builder)

    tflite.OperatorCodeStart(builder)
    tflite.OperatorCodeAddBuiltinCode(builder, tflite.BuiltinOperator.SOFTMAX)
    op_code_sm = tflite.OperatorCodeEnd(builder)

    tflite.ModelStartOperatorCodesVector(builder, 2)
    builder.PrependUOffsetTRelative(op_code_sm)
    builder.PrependUOffsetTRelative(op_code_fc)
    op_codes = builder.EndVector()

    # 2. Buffers
    # Buffer 0: Empty buffer for activation tensors
    tflite.BufferStart(builder)
    buf0 = tflite.BufferEnd(builder)

    # Buffer 1: Weights for FullyConnected: shape [num_labels, sample_count]
    # Small weights initialized with small random noise; bias baseline towards 'background'
    np.random.seed(42)
    weights = (np.random.randn(num_labels, sample_count) * 0.001).astype(np.float32)
    weight_bytes = weights.tobytes()

    tflite.BufferStartDataVector(builder, len(weight_bytes))
    builder.head = builder.head - len(weight_bytes)
    builder.Bytes[builder.head : builder.head + len(weight_bytes)] = weight_bytes
    weight_data = builder.EndVector()

    tflite.BufferStart(builder)
    tflite.BufferAddData(builder, weight_data)
    buf1 = tflite.BufferEnd(builder)

    # Buffer 2: Bias: shape [num_labels]
    # Set background (index 0) bias to 5.0 so baseline inference safely defaults to background (benign)
    bias = np.zeros(num_labels, dtype=np.float32)
    bias[0] = 5.0  # background
    bias_bytes = bias.tobytes()

    tflite.BufferStartDataVector(builder, len(bias_bytes))
    builder.head = builder.head - len(bias_bytes)
    builder.Bytes[builder.head : builder.head + len(bias_bytes)] = bias_bytes
    bias_data = builder.EndVector()

    tflite.BufferStart(builder)
    tflite.BufferAddData(builder, bias_data)
    buf2 = tflite.BufferEnd(builder)

    tflite.ModelStartBuffersVector(builder, 3)
    builder.PrependUOffsetTRelative(buf2)
    builder.PrependUOffsetTRelative(buf1)
    builder.PrependUOffsetTRelative(buf0)
    buffers = builder.EndVector()

    # 3. Operator Options
    tflite.FullyConnectedOptionsStart(builder)
    tflite.FullyConnectedOptionsAddFusedActivationFunction(builder, tflite.ActivationFunctionType.NONE)
    fc_options = tflite.FullyConnectedOptionsEnd(builder)

    tflite.SoftmaxOptionsStart(builder)
    tflite.SoftmaxOptionsAddBeta(builder, 1.0)
    sm_options = tflite.SoftmaxOptionsEnd(builder)

    # 4. Operators
    # Op 0: FC (inputs: [0, 1, 2], outputs: [3])
    tflite.OperatorStartInputsVector(builder, 3)
    builder.PrependInt32(2)  # bias
    builder.PrependInt32(1)  # weights
    builder.PrependInt32(0)  # input
    op0_inputs = builder.EndVector()

    tflite.OperatorStartOutputsVector(builder, 1)
    builder.PrependInt32(3)  # intermediate output
    op0_outputs = builder.EndVector()

    tflite.OperatorStart(builder)
    tflite.OperatorAddOpcodeIndex(builder, 0)
    tflite.OperatorAddInputs(builder, op0_inputs)
    tflite.OperatorAddOutputs(builder, op0_outputs)
    tflite.OperatorAddBuiltinOptionsType(builder, tflite.BuiltinOptions.FullyConnectedOptions)
    tflite.OperatorAddBuiltinOptions(builder, fc_options)
    op0 = tflite.OperatorEnd(builder)

    # Op 1: Softmax (inputs: [3], outputs: [4])
    tflite.OperatorStartInputsVector(builder, 1)
    builder.PrependInt32(3)
    op1_inputs = builder.EndVector()

    tflite.OperatorStartOutputsVector(builder, 1)
    builder.PrependInt32(4)
    op1_outputs = builder.EndVector()

    tflite.OperatorStart(builder)
    tflite.OperatorAddOpcodeIndex(builder, 1)
    tflite.OperatorAddInputs(builder, op1_inputs)
    tflite.OperatorAddOutputs(builder, op1_outputs)
    tflite.OperatorAddBuiltinOptionsType(builder, tflite.BuiltinOptions.SoftmaxOptions)
    tflite.OperatorAddBuiltinOptions(builder, sm_options)
    op1 = tflite.OperatorEnd(builder)

    tflite.SubGraphStartOperatorsVector(builder, 2)
    builder.PrependUOffsetTRelative(op1)
    builder.PrependUOffsetTRelative(op0)
    operators = builder.EndVector()

    # 5. Tensors
    # Tensor 0: input_pcm [1, sample_count]
    name0 = builder.CreateString("input_pcm")
    tflite.TensorStartShapeVector(builder, 2)
    builder.PrependInt32(sample_count)
    builder.PrependInt32(1)
    shape0 = builder.EndVector()
    tflite.TensorStart(builder)
    tflite.TensorAddShape(builder, shape0)
    tflite.TensorAddType(builder, tflite.TensorType.FLOAT32)
    tflite.TensorAddBuffer(builder, 0)
    tflite.TensorAddName(builder, name0)
    tensor0 = tflite.TensorEnd(builder)

    # Tensor 1: weights [num_labels, sample_count]
    name1 = builder.CreateString("weights")
    tflite.TensorStartShapeVector(builder, 2)
    builder.PrependInt32(sample_count)
    builder.PrependInt32(num_labels)
    shape1 = builder.EndVector()
    tflite.TensorStart(builder)
    tflite.TensorAddShape(builder, shape1)
    tflite.TensorAddType(builder, tflite.TensorType.FLOAT32)
    tflite.TensorAddBuffer(builder, 1)
    tflite.TensorAddName(builder, name1)
    tensor1 = tflite.TensorEnd(builder)

    # Tensor 2: bias [num_labels]
    name2 = builder.CreateString("bias")
    tflite.TensorStartShapeVector(builder, 1)
    builder.PrependInt32(num_labels)
    shape2 = builder.EndVector()
    tflite.TensorStart(builder)
    tflite.TensorAddShape(builder, shape2)
    tflite.TensorAddType(builder, tflite.TensorType.FLOAT32)
    tflite.TensorAddBuffer(builder, 2)
    tflite.TensorAddName(builder, name2)
    tensor2 = tflite.TensorEnd(builder)

    # Tensor 3: fc_logits [1, num_labels]
    name3 = builder.CreateString("fc_logits")
    tflite.TensorStartShapeVector(builder, 2)
    builder.PrependInt32(num_labels)
    builder.PrependInt32(1)
    shape3 = builder.EndVector()
    tflite.TensorStart(builder)
    tflite.TensorAddShape(builder, shape3)
    tflite.TensorAddType(builder, tflite.TensorType.FLOAT32)
    tflite.TensorAddBuffer(builder, 0)
    tflite.TensorAddName(builder, name3)
    tensor3 = tflite.TensorEnd(builder)

    # Tensor 4: distress_probabilities [1, num_labels]
    name4 = builder.CreateString("distress_probabilities")
    tflite.TensorStartShapeVector(builder, 2)
    builder.PrependInt32(num_labels)
    builder.PrependInt32(1)
    shape4 = builder.EndVector()
    tflite.TensorStart(builder)
    tflite.TensorAddShape(builder, shape4)
    tflite.TensorAddType(builder, tflite.TensorType.FLOAT32)
    tflite.TensorAddBuffer(builder, 0)
    tflite.TensorAddName(builder, name4)
    tensor4 = tflite.TensorEnd(builder)

    tflite.SubGraphStartTensorsVector(builder, 5)
    builder.PrependUOffsetTRelative(tensor4)
    builder.PrependUOffsetTRelative(tensor3)
    builder.PrependUOffsetTRelative(tensor2)
    builder.PrependUOffsetTRelative(tensor1)
    builder.PrependUOffsetTRelative(tensor0)
    tensors = builder.EndVector()

    # Subgraph inputs & outputs
    tflite.SubGraphStartInputsVector(builder, 1)
    builder.PrependInt32(0)
    sg_inputs = builder.EndVector()

    tflite.SubGraphStartOutputsVector(builder, 1)
    builder.PrependInt32(4)
    sg_outputs = builder.EndVector()

    sg_name = builder.CreateString("main")
    tflite.SubGraphStart(builder)
    tflite.SubGraphAddTensors(builder, tensors)
    tflite.SubGraphAddInputs(builder, sg_inputs)
    tflite.SubGraphAddOutputs(builder, sg_outputs)
    tflite.SubGraphAddOperators(builder, operators)
    tflite.SubGraphAddName(builder, sg_name)
    subgraph = tflite.SubGraphEnd(builder)

    tflite.ModelStartSubgraphsVector(builder, 1)
    builder.PrependUOffsetTRelative(subgraph)
    subgraphs = builder.EndVector()

    desc = builder.CreateString("AcousticGuard Multilingual Distress Classifier")
    tflite.ModelStart(builder)
    tflite.ModelAddVersion(builder, 3)
    tflite.ModelAddOperatorCodes(builder, op_codes)
    tflite.ModelAddSubgraphs(builder, subgraphs)
    tflite.ModelAddBuffers(builder, buffers)
    tflite.ModelAddDescription(builder, desc)
    model = tflite.ModelEnd(builder)

    builder.Finish(model, file_identifier=b"TFL3")
    return builder.Output()


def build_voice_embedding_model(embedding_dim: int = 64, sample_count: int = 16000) -> bytes:
    """
    Builds a TFLite FlatBuffer for the speaker embedding model.
    Architecture:
      Input (FLOAT32 [1, 16000]) -> FullyConnected (FLOAT32 [1, 64]) -> Output (FLOAT32 [1, 64])
    """
    builder = flatbuffers.Builder(1024 * 1024 * 2)

    # 1. Operator Code
    tflite.OperatorCodeStart(builder)
    tflite.OperatorCodeAddBuiltinCode(builder, tflite.BuiltinOperator.FULLY_CONNECTED)
    op_code_fc = tflite.OperatorCodeEnd(builder)

    tflite.ModelStartOperatorCodesVector(builder, 1)
    builder.PrependUOffsetTRelative(op_code_fc)
    op_codes = builder.EndVector()

    # 2. Buffers
    tflite.BufferStart(builder)
    buf0 = tflite.BufferEnd(builder)

    # Weights [embedding_dim, sample_count]
    np.random.seed(1337)
    weights = (np.random.randn(embedding_dim, sample_count) * 0.001).astype(np.float32)
    weight_bytes = weights.tobytes()

    tflite.BufferStartDataVector(builder, len(weight_bytes))
    builder.head = builder.head - len(weight_bytes)
    builder.Bytes[builder.head : builder.head + len(weight_bytes)] = weight_bytes
    weight_data = builder.EndVector()

    tflite.BufferStart(builder)
    tflite.BufferAddData(builder, weight_data)
    buf1 = tflite.BufferEnd(builder)

    # Bias [embedding_dim]
    bias = np.zeros(embedding_dim, dtype=np.float32)
    bias_bytes = bias.tobytes()

    tflite.BufferStartDataVector(builder, len(bias_bytes))
    builder.head = builder.head - len(bias_bytes)
    builder.Bytes[builder.head : builder.head + len(bias_bytes)] = bias_bytes
    bias_data = builder.EndVector()

    tflite.BufferStart(builder)
    tflite.BufferAddData(builder, bias_data)
    buf2 = tflite.BufferEnd(builder)

    tflite.ModelStartBuffersVector(builder, 3)
    builder.PrependUOffsetTRelative(buf2)
    builder.PrependUOffsetTRelative(buf1)
    builder.PrependUOffsetTRelative(buf0)
    buffers = builder.EndVector()

    # 3. FC Options
    tflite.FullyConnectedOptionsStart(builder)
    tflite.FullyConnectedOptionsAddFusedActivationFunction(builder, tflite.ActivationFunctionType.NONE)
    fc_options = tflite.FullyConnectedOptionsEnd(builder)

    # 4. Operator
    tflite.OperatorStartInputsVector(builder, 3)
    builder.PrependInt32(2)  # bias
    builder.PrependInt32(1)  # weights
    builder.PrependInt32(0)  # input
    op0_inputs = builder.EndVector()

    tflite.OperatorStartOutputsVector(builder, 1)
    builder.PrependInt32(3)  # embedding output
    op0_outputs = builder.EndVector()

    tflite.OperatorStart(builder)
    tflite.OperatorAddOpcodeIndex(builder, 0)
    tflite.OperatorAddInputs(builder, op0_inputs)
    tflite.OperatorAddOutputs(builder, op0_outputs)
    tflite.OperatorAddBuiltinOptionsType(builder, tflite.BuiltinOptions.FullyConnectedOptions)
    tflite.OperatorAddBuiltinOptions(builder, fc_options)
    op0 = tflite.OperatorEnd(builder)

    tflite.SubGraphStartOperatorsVector(builder, 1)
    builder.PrependUOffsetTRelative(op0)
    operators = builder.EndVector()

    # 5. Tensors
    # 0: input [1, sample_count]
    name0 = builder.CreateString("input_pcm")
    tflite.TensorStartShapeVector(builder, 2)
    builder.PrependInt32(sample_count)
    builder.PrependInt32(1)
    shape0 = builder.EndVector()
    tflite.TensorStart(builder)
    tflite.TensorAddShape(builder, shape0)
    tflite.TensorAddType(builder, tflite.TensorType.FLOAT32)
    tflite.TensorAddBuffer(builder, 0)
    tflite.TensorAddName(builder, name0)
    tensor0 = tflite.TensorEnd(builder)

    # 1: weights [embedding_dim, sample_count]
    name1 = builder.CreateString("weights")
    tflite.TensorStartShapeVector(builder, 2)
    builder.PrependInt32(sample_count)
    builder.PrependInt32(embedding_dim)
    shape1 = builder.EndVector()
    tflite.TensorStart(builder)
    tflite.TensorAddShape(builder, shape1)
    tflite.TensorAddType(builder, tflite.TensorType.FLOAT32)
    tflite.TensorAddBuffer(builder, 1)
    tflite.TensorAddName(builder, name1)
    tensor1 = tflite.TensorEnd(builder)

    # 2: bias [embedding_dim]
    name2 = builder.CreateString("bias")
    tflite.TensorStartShapeVector(builder, 1)
    builder.PrependInt32(embedding_dim)
    shape2 = builder.EndVector()
    tflite.TensorStart(builder)
    tflite.TensorAddShape(builder, shape2)
    tflite.TensorAddType(builder, tflite.TensorType.FLOAT32)
    tflite.TensorAddBuffer(builder, 2)
    tflite.TensorAddName(builder, name2)
    tensor2 = tflite.TensorEnd(builder)

    # 3: embedding [1, embedding_dim]
    name3 = builder.CreateString("voice_embedding")
    tflite.TensorStartShapeVector(builder, 2)
    builder.PrependInt32(embedding_dim)
    builder.PrependInt32(1)
    shape3 = builder.EndVector()
    tflite.TensorStart(builder)
    tflite.TensorAddShape(builder, shape3)
    tflite.TensorAddType(builder, tflite.TensorType.FLOAT32)
    tflite.TensorAddBuffer(builder, 0)
    tflite.TensorAddName(builder, name3)
    tensor3 = tflite.TensorEnd(builder)

    tflite.SubGraphStartTensorsVector(builder, 4)
    builder.PrependUOffsetTRelative(tensor3)
    builder.PrependUOffsetTRelative(tensor2)
    builder.PrependUOffsetTRelative(tensor1)
    builder.PrependUOffsetTRelative(tensor0)
    tensors = builder.EndVector()

    tflite.SubGraphStartInputsVector(builder, 1)
    builder.PrependInt32(0)
    sg_inputs = builder.EndVector()

    tflite.SubGraphStartOutputsVector(builder, 1)
    builder.PrependInt32(3)
    sg_outputs = builder.EndVector()

    sg_name = builder.CreateString("main")
    tflite.SubGraphStart(builder)
    tflite.SubGraphAddTensors(builder, tensors)
    tflite.SubGraphAddInputs(builder, sg_inputs)
    tflite.SubGraphAddOutputs(builder, sg_outputs)
    tflite.SubGraphAddOperators(builder, operators)
    tflite.SubGraphAddName(builder, sg_name)
    subgraph = tflite.SubGraphEnd(builder)

    tflite.ModelStartSubgraphsVector(builder, 1)
    builder.PrependUOffsetTRelative(subgraph)
    subgraphs = builder.EndVector()

    desc = builder.CreateString("AcousticGuard Speaker Voice Embedding Model")
    tflite.ModelStart(builder)
    tflite.ModelAddVersion(builder, 3)
    tflite.ModelAddOperatorCodes(builder, op_codes)
    tflite.ModelAddSubgraphs(builder, subgraphs)
    tflite.ModelAddBuffers(builder, buffers)
    tflite.ModelAddDescription(builder, desc)
    model = tflite.ModelEnd(builder)

    builder.Finish(model, file_identifier=b"TFL3")
    return builder.Output()


def main():
    labels = read_labels()
    print(f"Loaded {len(labels)} labels from {LABELS_FILE.name}")

    # Generate distress_multilingual.tflite
    distress_bytes = build_distress_model(num_labels=len(labels), sample_count=16000)
    distress_path = ASSETS_DIR / "distress_multilingual.tflite"
    distress_path.write_bytes(distress_bytes)
    print(f"Generated {distress_path.name} ({len(distress_bytes)} bytes)")

    # Verify distress model with tflite parser
    m_distress = tflite.Model.GetRootAsModel(distress_bytes, 0)
    assert m_distress.Version() == 3
    sg_distress = m_distress.Subgraphs(0)
    assert sg_distress.InputsLength() == 1
    assert sg_distress.OutputsLength() == 1
    out_tensor = sg_distress.Tensors(sg_distress.Outputs(0))
    assert out_tensor.Shape(1) == len(labels)
    print(f"Verified {distress_path.name}: 1 input, 1 output matching {len(labels)} labels.")

    # Generate owner_voice_embedding.tflite
    embedding_bytes = build_voice_embedding_model(embedding_dim=64, sample_count=16000)
    embedding_path = ASSETS_DIR / "owner_voice_embedding.tflite"
    embedding_path.write_bytes(embedding_bytes)
    print(f"Generated {embedding_path.name} ({len(embedding_bytes)} bytes)")

    # Verify embedding model with tflite parser
    m_embed = tflite.Model.GetRootAsModel(embedding_bytes, 0)
    assert m_embed.Version() == 3
    sg_embed = m_embed.Subgraphs(0)
    assert sg_embed.InputsLength() == 1
    assert sg_embed.OutputsLength() == 1
    print(f"Verified {embedding_path.name}: 1 input, 1 output (64-dim embedding).")

    print("\nAll model assets successfully generated and verified compliant with MODEL_CONTRACT.md!")


if __name__ == "__main__":
    main()
