"""Case-preserving corpus CER/WER for handwriting evaluation; no accuracy claims."""
import unicodedata


def normalize(text):
    return ' '.join(unicodedata.normalize('NFC',text).split())


def distance(reference,prediction):
    previous=list(range(len(prediction)+1))
    for i,expected in enumerate(reference,1):
        current=[i]
        for j,actual in enumerate(prediction,1):
            current.append(min(current[-1]+1,previous[j]+1,previous[j-1]+(expected!=actual)))
        previous=current
    return previous[-1]


def evaluate(references,predictions):
    if set(references)!=set(predictions):raise ValueError('Prediction IDs must exactly match the held-out reference IDs')
    if not references:raise ValueError('An empty test set cannot establish accuracy')
    chars=words=char_errors=word_errors=exact=0
    for key,raw in references.items():
        ref=normalize(raw);pred=normalize(predictions[key])
        if not ref:raise ValueError('Blank reference transcription')
        chars+=len(ref);words+=len(ref.split())
        char_errors+=distance(ref,pred);word_errors+=distance(ref.split(),pred.split())
        exact+=ref==pred
    return {'samples':len(references),'characters':chars,'words':words,
            'characterErrors':char_errors,'wordErrors':word_errors,
            'cer':char_errors/chars,'wer':word_errors/words,'exactLineRate':exact/len(references),
            'normalization':'NFC, collapse whitespace, preserve case and punctuation'}
