import { createRoot } from 'react-dom/client';
import { App } from './App.tsx';

const container = document.getElementById('root');
if (container === null) throw new Error('#root container missing');
createRoot(container).render(<App />);
