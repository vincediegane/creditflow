import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { InputAdornment, TextField } from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';

/** Recherche globale : nom, telephone, produit ou reference de contrat. */
export default function GlobalSearchBar() {
  const [value, setValue] = useState('');
  const navigate = useNavigate();

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    const query = value.trim();
    if (query) {
      navigate(`/recherche?q=${encodeURIComponent(query)}`);
    }
  };

  return (
    <form onSubmit={submit} style={{ flexGrow: 1, maxWidth: 480 }}>
      <TextField
        fullWidth
        placeholder="Rechercher un client, un téléphone, un produit, un contrat…"
        value={value}
        onChange={(event) => setValue(event.target.value)}
        InputProps={{
          startAdornment: (
            <InputAdornment position="start">
              <SearchIcon fontSize="small" />
            </InputAdornment>
          ),
        }}
      />
    </form>
  );
}
