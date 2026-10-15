"""Explicit aligned seq2seq CE, avoiding generic causal-LM target shifting."""


def aligned_token_loss(logits, labels):
    import torch.nn.functional as functional
    if logits.ndim != 3 or labels.ndim != 2 or logits.shape[:2] != labels.shape:
        raise ValueError('One logit vector must correspond to each unshifted target token')
    return functional.cross_entropy(logits.float().reshape(-1, logits.shape[-1]),
                                    labels.reshape(-1).to(logits.device), ignore_index=-100)


def training_loss(model, labels, **inputs):
    # The decoder consumes [start, target[:-1]] and predicts target at the SAME
    # positions. Do not send labels into the model's generic loss dispatcher:
    # Transformers 4.57.1 defaults this wrapper to a second causal target shift.
    decoder_inputs = model.prepare_decoder_input_ids_from_labels(labels=labels)
    outputs = model(decoder_input_ids=decoder_inputs, return_dict=True, use_cache=False, **inputs)
    return aligned_token_loss(outputs.logits, labels)
