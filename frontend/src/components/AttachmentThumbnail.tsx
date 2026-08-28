import { Box, type SxProps, type Theme } from '@mui/material';

import { useAuthenticatedFile } from '../hooks/useAuthenticatedFile';
import type { SaleAttachment } from '../types';

interface Props {
  attachment: Pick<SaleAttachment, 'fileUrl'>;
  alt: string;
  sx?: SxProps<Theme>;
}

export default function AttachmentThumbnail({ attachment, alt, sx }: Props) {
  const { url } = useAuthenticatedFile(attachment.fileUrl);

  return (
    <Box
      component="img"
      src={url}
      alt={alt}
      sx={sx ?? { width: '100%', height: 120, objectFit: 'cover', display: 'block' }}
    />
  );
}
