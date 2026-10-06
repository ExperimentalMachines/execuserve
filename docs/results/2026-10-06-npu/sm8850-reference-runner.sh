# On the phone: three runs each of the short (21 tokens, 128 out) and long (697 tokens, 64 out)
# prompts on the HTP, as token ids, greedy, Qwen3 stop tokens.
cd /data/local/tmp/qnn; export LD_LIBRARY_PATH=$PWD ADSP_LIBRARY_PATH=$PWD
for i in 1 2 3; do for spec in short:21:128 long:697:64; do
  p=${spec%%:*}; rest=${spec#*:}; n=${rest%%:*}; out=${rest#*:}
  ./qnn_llama_runner --decoder_model_version qwen3 --model_path model.pte --tokenizer_path tokenizer.json \
    --tokenized_prompt $p.tok --seq_len $((n + out)) --temperature 0 --eval_mode 1 \
    --output_path npu-$i-$p.txt --performance_output_path npu-$i-$p.perf > npu-$i-$p.log 2>&1
  echo "$i $p exit=$? $(grep -o 'PyTorchObserver.*' npu-$i-$p.log)"
done; done
