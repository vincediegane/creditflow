import { Avatar, type SxProps, type Theme } from '@mui/material';

import { useAuthenticatedFile } from '../hooks/useAuthenticatedFile';
import type { Customer } from '../types';
import { initials } from '../utils/format';

interface Props {
  customer: Pick<Customer, 'photoUrl' | 'fullName'>;
  sx?: SxProps<Theme>;
}

export default function CustomerAvatar({ customer, sx }: Props) {
  const { url } = useAuthenticatedFile(customer.photoUrl);

  return (
    <Avatar src={url} sx={sx ?? { width: 40, height: 40, bgcolor: 'primary.light' }}>
      {initials(customer.fullName)}
    </Avatar>
  );
}
