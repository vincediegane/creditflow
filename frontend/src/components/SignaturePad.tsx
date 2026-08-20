import { useEffect, useRef, useState } from 'react';
import { Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Typography } from '@mui/material';

interface Props {
  open: boolean;
  onClose: () => void;
  onValidate: (file: File) => void;
}

/** Zone de signature tactile : trace au doigt/stylet, exportée en PNG. */
export default function SignaturePad({ open, onClose, onValidate }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const drawing = useRef(false);
  const [hasDrawn, setHasDrawn] = useState(false);

  const context = () => canvasRef.current?.getContext('2d') ?? null;

  const pointerPosition = (event: React.PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) {
      return { x: 0, y: 0 };
    }
    const rect = canvas.getBoundingClientRect();
    return {
      x: ((event.clientX - rect.left) / rect.width) * canvas.width,
      y: ((event.clientY - rect.top) / rect.height) * canvas.height,
    };
  };

  const startDrawing = (event: React.PointerEvent<HTMLCanvasElement>) => {
    const ctx = context();
    if (!ctx) {
      return;
    }
    drawing.current = true;
    const { x, y } = pointerPosition(event);
    ctx.beginPath();
    ctx.moveTo(x, y);
  };

  const draw = (event: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawing.current) {
      return;
    }
    const ctx = context();
    if (!ctx) {
      return;
    }
    const { x, y } = pointerPosition(event);
    ctx.lineWidth = 2.5;
    ctx.lineCap = 'round';
    ctx.strokeStyle = '#1a1a1a';
    ctx.lineTo(x, y);
    ctx.stroke();
    setHasDrawn(true);
  };

  const stopDrawing = () => {
    drawing.current = false;
  };

  const clear = () => {
    const canvas = canvasRef.current;
    const ctx = context();
    if (!canvas || !ctx) {
      return;
    }
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    setHasDrawn(false);
  };

  useEffect(() => {
    if (open) {
      clear();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const validate = () => {
    const canvas = canvasRef.current;
    if (!canvas) {
      return;
    }
    canvas.toBlob((blob) => {
      if (blob) {
        onValidate(new File([blob], 'signature.png', { type: 'image/png' }));
      }
    }, 'image/png');
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Signature du client</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5}>
          <Typography variant="body2" color="text.secondary">
            Faites signer le client directement sur l'écran.
          </Typography>
          <canvas
            ref={canvasRef}
            width={600}
            height={280}
            style={{ width: '100%', height: 280, border: '1px solid #ccc', borderRadius: 8, touchAction: 'none' }}
            onPointerDown={startDrawing}
            onPointerMove={draw}
            onPointerUp={stopDrawing}
            onPointerLeave={stopDrawing}
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <Button onClick={onClose} color="inherit">
          Annuler
        </Button>
        <Button onClick={clear} disabled={!hasDrawn}>
          Effacer
        </Button>
        <Button variant="contained" onClick={validate} disabled={!hasDrawn}>
          Valider
        </Button>
      </DialogActions>
    </Dialog>
  );
}
